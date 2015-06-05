package sres;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RemoteEvaluationWorker {
	private static final Logger logger = LogManager.getLogger(RemoteExecutionTracker.class);
	public static final int queueMultiplier = 3;
	public static final int shutdownTimeout = 20;

	String serverName;
	int port;
	String secret;

	Socket socket;
	ObjectInputStream in;
	ObjectOutputStream out;

	final ThreadPoolExecutor tpe;
	final BlockingQueue<Runnable> workQueue;
	final BlockingQueue<RemoteResult> resultQueue;

	final SendingThread sendThread;
	final ReceivingThread recvThread;

	volatile boolean shutdown = false;

	public static void main(String[] args) {

		// Define command line options
		Options options = new Options();

		Option secret = Option.builder("s").required().longOpt("secret")
				.desc("The shared secret used for authentication").hasArg().numberOfArgs(1).build();
		options.addOption(secret);

		DefaultParser dp = new DefaultParser();
		HelpFormatter hf = new HelpFormatter();
		CommandLine cmd;
		try {
			cmd = dp.parse(options, args);
		} catch (ParseException e) {
			hf.printHelp("-s [secret] [host] [port]", options);
			e.printStackTrace();
			return;
		}

		String[] leftovers = cmd.getArgs();
		if (leftovers.length != 2) {
			hf.printHelp("-s [secret] [host] [port]", options);
			return;
		}

		try {
			@SuppressWarnings("unused")
			InetAddress address = InetAddress.getByName(leftovers[0]);
		} catch (UnknownHostException e) {
			System.out.println("Unknown host: " + leftovers[0]);
			return;
		}

		int port = -1;
		try {
			port = Integer.parseInt(leftovers[1]);
		} catch (NumberFormatException nfe) {
			System.out.println("Illegal port: " + leftovers[1]);
		}

		
		RemoteEvaluationWorker rew;
		try {
			rew = new RemoteEvaluationWorker(leftovers[0], port, cmd.getOptionValue("s"));
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		
		while(true) {
			try {
				rew.awaitTermination();
			} catch (InterruptedException e) {
				e.printStackTrace();
				break;
			}
		}

	}

	public RemoteEvaluationWorker(String serverName, int port, String secret) throws UnknownHostException, IOException {
		this.serverName = serverName;
		this.port = port;
		this.secret = secret;

		setup();
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			logger.error("Interrupted while initializing", e);
		}
		if (socket.isClosed())
			throw new RuntimeException("Socket was closed after connection setup");

		OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
		int numCPUs = operatingSystemMXBean.getAvailableProcessors();
		int queueSize = numCPUs * queueMultiplier;

		workQueue = new ArrayBlockingQueue<>(queueSize);
		resultQueue = new ArrayBlockingQueue<>(queueSize * 4);

		tpe = new ThreadPoolExecutor(numCPUs, numCPUs, 5, TimeUnit.MINUTES, workQueue);

		sendThread = new SendingThread();
		recvThread = new ReceivingThread();

		recvThread.start();
		sendThread.start();

	}

	public void setup() throws UnknownHostException, IOException {
		socket = new Socket(serverName, port);
		out = new ObjectOutputStream(socket.getOutputStream());
		in = new ObjectInputStream(socket.getInputStream());

		try {
			Object o = in.readObject();
			AuthenticationRequest ar = (AuthenticationRequest) o;
			ar.calculateResult(secret);
			out.writeObject(ar);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public void awaitTermination() throws InterruptedException {
		while (!tpe.isTerminated()) {
			tpe.awaitTermination(10, TimeUnit.SECONDS);
		}
		sendThread.join();
		recvThread.join();
	}

	private class ResultHelper implements Runnable {
		ProducesRemoteResult prr;
		Queue<RemoteResult> resultQueue;

		ResultHelper(ProducesRemoteResult prr, Queue<RemoteResult> resultQueue) {
			this.prr = prr;
			this.resultQueue = resultQueue;
		}

		@Override
		public void run() {
			prr.run();
			RemoteResult rr = prr.getRemoteResult();
			resultQueue.add(rr);
		}

	}

	class ReceivingThread extends Thread {
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
						if (recv instanceof ProducesRemoteResult) {
							ProducesRemoteResult prr = (ProducesRemoteResult) recv;
							ResultHelper rh = new ResultHelper(prr, resultQueue);
							tpe.submit(rh);
						} else {
							logger.error("Received object other than ProducesRemoteResult, ignoring");
						}
					} else {
						shutdown();
						return;
					}

					if (recv != null) {
						timeWaiting = 0;
					} else {
						timeWaiting += timeout;
					}
				} catch (IOException e) {
					if (e instanceof EOFException) {
						logger.info("Disconnecting from " + socket.getRemoteSocketAddress());
					} else {
						logger.error("IOException waiting to receive from remote at " + socket.getRemoteSocketAddress()
								+ "; shutting down connection, Cause was: ", e);
					}
					shutdown();
					return;
				} catch (ClassNotFoundException e) {
					logger.error("ClassNotFoundException receiving from remote at " + socket.getRemoteSocketAddress()
							+ "; shutting down connection, Cause was: ", e);
					shutdown();
					return;
				}

			}
		}
	}

	class SendingThread extends Thread {
		volatile boolean shutdown = false;
		static final int timeout = 2;

		int timeWaiting = 0;

		@Override
		public void run() {
			while (true) {
				try {
					if (shutdown == true)
						return;

					RemoteResult result = resultQueue.poll(timeout, TimeUnit.SECONDS);
					if (result != null) {
						out.writeObject(result);
						timeWaiting = 0;
					} else {
						timeWaiting += timeout;
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					logger.error("IOException trying to send to remote at " + socket.getRemoteSocketAddress()
							+ "; shutting down connection", e);
					shutdown();
					return;
				}

			}
		}
	}

	public void shutdown() {
		logger.info("Shutting down ThreadPool, waiting up to " + shutdownTimeout + " seconds for completion");
		shutdown = true;

		if (sendThread != null)
			sendThread.shutdown = true;

		tpe.shutdown();
		try {
			tpe.awaitTermination(shutdownTimeout, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			logger.error("InterruptedException while shutting down ThreadPool, cause is ", e);
		}

		if (!tpe.isTerminated()) {
			logger.error("ThreadPool still running after timeout expired, forcing shutdown");
			tpe.shutdownNow();
		}

		if (!tpe.isTerminated()) {
			logger.fatal("ThreadPool could not be terminated even forcefully. forcing system exit");
			System.exit(-1);
		}

		try {
			if (out != null)
				out.close();
		} catch (IOException e) {
			logger.error("IOException trying to close output stream to " + socket.getRemoteSocketAddress(), e);
		}

		try {
			if (in != null)
				in.close();
		} catch (IOException e) {
			logger.error("IOException trying to close input stream from " + socket.getRemoteSocketAddress(), e);
		}

		try {
			if (socket != null)
				socket.close();
		} catch (IOException e) {
			logger.error("IOException trying to close socket with " + socket.getRemoteSocketAddress(), e);
		}

	}

}
