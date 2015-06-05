package sres;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LocalGridExecutor extends ThreadPoolExecutor implements NonBlockingExecutorService {
	private static final Logger logger = LogManager.getLogger(LocalGridExecutor.class);

	public LocalGridExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
	}

	public LocalGridExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler);
	}

	public LocalGridExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
	}

	public LocalGridExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
	}

	private class ResultHelper implements Runnable {
		Runnable r;

		ResultHelper(Runnable r) {
			this.r = r;
		}
		
		@Override
		public void run() {
			r.run();
			if (r instanceof RemoteCallable) {
				RemoteCallable rc = (RemoteCallable) r;
				rc.processResult.accept(rc.result);
			}
		}
	}
	
	@Override
	public boolean offer(Runnable r) {
		return execute(r,false);
	}

	private boolean execute(Runnable command, boolean block) {
		boolean toReturn = false;

		try {
			toReturn = execute2(command, block);
		} catch (InterruptedException e) {
			logger.info("Interrupted while (waiting to?) put " + command + " in queue ", e);
		}

		return toReturn;
	}

	private boolean execute2(Runnable command, boolean block) throws InterruptedException {
		BlockingQueue<Runnable> queue = this.getQueue();
		if (block) {
			super.execute(new ResultHelper(command));
			return true;
		} 
		
		if (queue.remainingCapacity() > 0) {
			super.execute(new ResultHelper(command));
			return true;
		} else {
			return false;
		}
	}

}
