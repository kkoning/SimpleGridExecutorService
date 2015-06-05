package sres;

import java.io.EOFException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RemoteExecutionTracker extends AbstractExecutorService implements NonBlockingExecutorService {

	private final int queueSize = 10;
	private static final Logger logger = LogManager.getLogger(RemoteExecutionTracker.class);

	Socket con;
	String secret;

	ObjectOutputStream out;
	ObjectInputStream in;

	SendingThread sendThread;
	ReceivingThread recvThread;

	GridExecutorService ges;

	volatile Boolean shuttingDown = false;
	volatile Boolean terminated = false;

	Boolean remoteShutdown = false;

	final BlockingQueue<Runnable> sendQueue;
	final Map<UUID, Runnable> outstandingRunnables;

	public RemoteExecutionTracker(GridExecutorService ges, Socket con, String secret) {
		this.con = con;
		this.secret = secret;
		this.ges = ges;
		sendQueue = new ArrayBlockingQueue<>(queueSize);
		outstandingRunnables = new HashMap<>();

		try {
			boolean initialized = initializeConnection();

			if (initialized) {
				sendThread = new SendingThread();
				recvThread = new ReceivingThread();

				recvThread.start();
				sendThread.start();

				ges.addRemote(this);
			}

		} catch (SecurityException e) {
			logger.error("SecurityException trying to initialize remote at " + con.getRemoteSocketAddress()
					+ "; shutting down connection", e);
			shutdown();
		} catch (ClassNotFoundException e) {
			logger.fatal("Could not find String.class?!", e);
			throw new RuntimeException(e);
		} catch (IOException e) {
			logger.error("IOException trying to initialize remote at " + con.getRemoteSocketAddress()
					+ "; shutting down connection", e);
			shutdown();
		}
	}

	@Override
	public boolean offer(Runnable r) {
		return execute(r, false);
	}

	@Override
	public void execute(Runnable command) {
		execute(command, true);
	}

	private boolean execute(Runnable command, boolean block) {
		synchronized (shuttingDown) {
			if (shuttingDown)
				throw new RejectedExecutionException();
		}

		if (command == null)
			throw new NullPointerException();

		boolean toReturn = false;

		try {
			if (command instanceof ProducesRemoteResult) {
				toReturn = execute2(command, block);
			} else {
				RemoteRunnable rr = new RemoteRunnable(command);
				toReturn = execute2(rr, block);
			}
		} catch (InterruptedException e) {
			logger.info("Interrupted while (waiting to?) put " + command + " in queue ", e);
		} catch (NotSerializableException e) {
			logger.error("Tried to execute a non-serializable Runnable: " + command);
		}

		return toReturn;
	}

	private boolean execute2(Runnable command, boolean block) throws InterruptedException {
		if (block) {
			sendQueue.put(command);
			return true;
		} else {
			return sendQueue.offer(command);
		}
	}

	@Override
	public void shutdown() {
		final long maxTimeToWait = 60_000; // 60s
		long timeWaited = 0;

		// Set state to make sure we don't accept any additional tasks
		synchronized (shuttingDown) {
			shuttingDown = true;
		}

		// Wait for outstanding tasks to finish
		while (!sendQueue.isEmpty()) {
			try {
				Thread.sleep(100);
				timeWaited += 100;
				if (timeWaited > maxTimeToWait) {
					logger.error("Did not shut down within " + (maxTimeToWait / 1000) + " second limit");
					shutdownNow();
					return;
				}
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting to shutdown, calling shutdownNow");
				shutdownNow();
				return;
			}
		}

		while (!outstandingRunnables.isEmpty()) {
			try {
				Thread.sleep(100);
				timeWaited += 100;
				if (timeWaited > maxTimeToWait) {
					logger.error("Did not shut down within " + (maxTimeToWait / 1000) + " second limit");
					shutdownNow();
					return;
				}
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting to shutdown, calling shutdownNow");
				shutdownNow();
				return;
			}
		}

		// Tell remote to shut down too
		if (!terminated) {
			try {
				ShutdownCommand sc = new ShutdownCommand((b) -> {
					logger.info("Received shutdown confirmation = " + b + " from remote");
					synchronized (remoteShutdown) {
						remoteShutdown = true;
					}
				});
				sendQueue.put(sc);
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting to shutdown, calling shutdownNow");
				shutdownNow();
				return;
			}
		}

		// Wait for remote shutdown task to return
		while (true) {
			try {
				synchronized (remoteShutdown) {
					if (remoteShutdown)
						break;
				}
				Thread.sleep(100);
				timeWaited += 100;
				if (timeWaited > maxTimeToWait) {
					logger.error("Did not shut down within " + (maxTimeToWait / 1000) + " second limit");
					shutdownNow();
					return;
				}
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting to shutdown, calling shutdownNow");
				shutdownNow(); // Make sure that shutdownnow forcibly marks
								// remote as shut down
				return;
			}
		}

		// clean up
		shutdownNow();
	}

	@Override
	public List<Runnable> shutdownNow() {
		if (sendThread != null)
			sendThread.shutdown = true;
		if (recvThread != null)
			recvThread.shutdown = true;

		try {
			if (out != null)
				out.close();
		} catch (IOException e) {
			logger.error("IOException trying to close output stream to " + con.getRemoteSocketAddress(), e);
		}

		try {
			if (in != null)
				in.close();
		} catch (IOException e) {
			logger.error("IOException trying to close input stream from " + con.getRemoteSocketAddress(), e);
		}

		try {
			if (con != null)
				con.close();
		} catch (IOException e) {
			logger.error("IOException trying to close socket with " + con.getRemoteSocketAddress(), e);
		}

		List<Runnable> uncomplete = new ArrayList<>();

		// Empty SendQueue
		sendQueue.drainTo(uncomplete);
		sendQueue.clear();

		// Empty outstanding queue
		for (UUID id : outstandingRunnables.keySet()) {
			Runnable r = outstandingRunnables.get(id);
			uncomplete.add(r);
			outstandingRunnables.remove(id);
		}
		outstandingRunnables.clear();

		terminated = true;

		return uncomplete;
	}

	@Override
	public boolean isShutdown() {
		return shuttingDown;
	}

	@Override
	public boolean isTerminated() {
		return terminated;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long ms = unit.convert(timeout, TimeUnit.MILLISECONDS);
		try {
			recvThread.join(ms);
			return true;
		} catch (InterruptedException e) {
			throw e;
		}
	}

	private boolean initializeConnection() throws IOException, SecurityException, ClassNotFoundException {
		boolean authenticated = false;

		System.out.println("Setting up object stream");
		out = new ObjectOutputStream(con.getOutputStream());
		in = new ObjectInputStream(con.getInputStream());

		AuthenticationRequest request = new AuthenticationRequest();
		out.writeObject(request);

		Object o = in.readObject();
		if (!(o instanceof AuthenticationRequest)) {
			logger.error("Did not receive exepected AuthenticationCommand from remote");
		} else {
			AuthenticationRequest response = (AuthenticationRequest) o;
			boolean uuidMatches = false;
			uuidMatches = request.salt.equals(response.salt);
			if (!uuidMatches) {
				logger.error("Possible replay attack from " + con.getRemoteSocketAddress());
			} else {
				authenticated = response.checkResult(secret);
			}
		}

		if (!authenticated) {
			logger.error("Remote at " + con.getRemoteSocketAddress() + " failed authentication");
			shutdownNow();
		}

		logger.info("Remote at " + con.getRemoteSocketAddress() + " initialized");

		return authenticated;

	}

	// Raw types shouldn't matter here. Since we're passing the results back
	// to the original RemoteCallable, the types should be the same.
	@SuppressWarnings({ "rawtypes" })
	private void processRemoteResult(RemoteResult result) {
		Runnable r = outstandingRunnables.get(result.getID());
		outstandingRunnables.remove(result.getID());
		if (r instanceof RemoteRunnable) {
			Exception e = ((RemoteRunnable) r).getException();
			if (e != null) {
				logger.error(r + " threw Exception: ", e);
			}
		} else if (r instanceof RemoteCallable) {
			Exception oe = ((RemoteCallable) r).getException();
			if (oe != null) {
				logger.error(r + " threw Exception: ", oe);
			}
			((RemoteCallable) r).processResponse(result);
		} else {
			throw new RuntimeException("Received unknown response " + result);
		}
	}

	class SendingThread extends Thread {
		volatile boolean shutdown = false;
		static final int pullFromQueueTimeout = 10_000; // 10s
		static final int pullFromQueueWarningInterval = 60_000; // 60s (1m)
		static final int sendToRemoteTimeout = 20; // 0.02s
		static final int sendToRemoteWarningInterval = 10000; // 10s

		@Override
		public void run() {
			while (true) {

				long timeWaitingForNewTask = 0;
				try {

					// if we have too many tasks outstanding, wait for there to
					// be room
					long timeWaitingOnRemote = 0;
					while (outstandingRunnables.size() > queueSize) {
						try {
							Thread.sleep(sendToRemoteTimeout);
							timeWaitingOnRemote += sendToRemoteTimeout;
						} catch (InterruptedException ie) {
							// Do nothing, shutdown check is in the finally
							// block
						} finally {
							// Watch for shutdown, so we don't wait forever
							if (shutdown == true)
								return;
							if ((timeWaitingOnRemote % sendToRemoteWarningInterval) == 0) {
								logger.warn(this + " waited " + (timeWaitingOnRemote / 1000)
										+ " seconds for a response from remote");
							}
						}
					}

					Runnable r = sendQueue.poll(pullFromQueueTimeout, TimeUnit.MILLISECONDS);

					if (r != null) {
						out.writeObject(r);
						if (r instanceof RemoteCallable) {
							@SuppressWarnings("rawtypes")
							RemoteCallable rc = (RemoteCallable) r;
							outstandingRunnables.put(rc.getID(), rc);
						}
						timeWaitingForNewTask = 0;
					} else {
						timeWaitingForNewTask += pullFromQueueTimeout;
						if ((timeWaitingForNewTask % pullFromQueueWarningInterval) == 0) {
							logger.warn(this + " waited " + (pullFromQueueWarningInterval / 1000)
									+ " seconds for a new task request");
						}
					}
				} catch (InterruptedException e) {
					// Do nothing, shutdown check is in finally block
				} catch (IOException e) {
					logger.error("IOException trying to send to remote at " + con.getRemoteSocketAddress()
							+ "; shutting down connection", e);
					shutdown();
				} finally {
					if (shutdown == true)
						return;
				}

			}
		}

	}

	class ReceivingThread extends Thread {
		volatile boolean shutdown = false;
		static final int timeout = 2;

		int timeWaiting = 0;

		@Override
		public void run() {
			while (true) {
				try {
					if (shutdown == true)
						return;

					Object recv = null;

					if (in != null) {
						recv = in.readObject();
						if (recv instanceof RemoteResult) {
							RemoteResult rr = (RemoteResult) recv;
							processRemoteResult(rr);
						} else {
							logger.error("Received object other than RemoteResult, ignoring.  Was: " + recv);
						}
					} else {
						// socket was closed
						shutdown();
					}

					if (recv != null) {
						timeWaiting = 0;
					} else {
						timeWaiting += timeout;
					}
				} catch (IOException e) {
					if (e instanceof EOFException) {
						logger.info("Disconnecting from " + con.getRemoteSocketAddress());
					} else {
						if (!isShutdown())
							logger.error("IOException waiting to receive from remote at " + con.getRemoteSocketAddress()
									+ "; shutting down connection, Cause was: ", e);
					}
					shutdownNow();
				} catch (ClassNotFoundException e) {
					logger.error("ClassNotFoundException receiving from remote at " + con.getRemoteSocketAddress()
							+ "; shutting down connection, Cause was: ", e);
					shutdownNow();
				}

			}
		}
	}

}
