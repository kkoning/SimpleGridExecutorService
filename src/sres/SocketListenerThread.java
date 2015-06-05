package sres;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Note that this class has fairly weak security precautions.  It is designed
 * to be run in a mostly-trusted environment.
 * 
 * @author kkoning
 *
 */
public class SocketListenerThread extends Thread {
	private static final Logger logger = LogManager.getLogger(RemoteExecutionTracker.class);
	
	GridExecutorService ges;
	ServerSocket serverSocket;
	String secret;
	private volatile boolean shutdown;
	
	public SocketListenerThread(GridExecutorService ges, int port, String secret) {
		this.ges = ges;
		this.secret = secret;
		try {
			serverSocket = new ServerSocket(port);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void run() {
		try {
			while (true) {
				System.out.println("Waiting to accept connections");
				acceptLoop();
				Thread.sleep(10);
			}
		} catch (IOException e) {
			if (!shutdown) {
				logger.error("Exception while listening for connections, terminating server", e);
				shutdown();
			}
			return;
		} catch (InterruptedException e) {
			logger.info("Interrupted while listening for connections", e);
		} finally {
		}

	}
	
	void shutdown() {
		shutdown = true;
		try {
			serverSocket.close();
		} catch (IOException e) {
			logger.fatal("Error while trying to close socket", e);
		}
	}

	private void acceptLoop() throws IOException {
		Socket connection = serverSocket.accept();
		System.out.println("Accepted remote connection from: " + connection.getRemoteSocketAddress());
		
		@SuppressWarnings("unused") // Constructer adds to ges only if succesfully initialized
		RemoteExecutionTracker ret = new RemoteExecutionTracker(ges, connection, secret);
	}

	
}
