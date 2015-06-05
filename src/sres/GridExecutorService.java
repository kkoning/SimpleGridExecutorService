package sres;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This is the central class on the server/requester side of things.  When running 
 * 
 * @author kkoning
 *
 */
public class GridExecutorService extends AbstractExecutorService {

	private static final int queueSizeMultiplier = 10;

	SocketListenerThread slt;

	LocalGridExecutor lge;
	BlockingQueue<Runnable> localTaskQueue;

	List<NonBlockingExecutorService> computeResources = new ArrayList<>();
	Integer computePos = 0;

	List<RemoteExecutionTracker> remotes = new ArrayList<>();

	volatile Boolean shutdown = false;
	volatile Boolean shuttingDownNow = false;
	volatile Boolean terminated = false;

	public GridExecutorService() {
		// Start our own execution services first.
		startLocalThreadPool(defaultNumThreads());
	}

	public GridExecutorService(int port, String secret, boolean localThreadPool, Optional<Integer> numLocalThreads) {
		if (localThreadPool) {
			if (numLocalThreads.isPresent())
				startLocalThreadPool(numLocalThreads.get());
			else
				startLocalThreadPool(defaultNumThreads());
		}
		
		slt = new SocketListenerThread(this, port, secret);
		slt.start();
	}

	private void startLocalThreadPool(int numThreads) {
		localTaskQueue = new ArrayBlockingQueue<>(numThreads * queueSizeMultiplier);
		lge = new LocalGridExecutor(numThreads, numThreads, 10, TimeUnit.SECONDS, localTaskQueue);
		lge.allowCoreThreadTimeOut(false);
		lge.prestartAllCoreThreads();
		computeResources.add(lge);
	}

	private int defaultNumThreads() {
		OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
		int numCPUs = operatingSystemMXBean.getAvailableProcessors();
		if (numCPUs > 1)
			numCPUs--;
		return numCPUs;
	}

	void addRemote(RemoteExecutionTracker ret) {
		computeResources.add(ret);
		remotes.add(ret);
	}

	boolean hasRemotes() {
		return !remotes.isEmpty();
	}

	@Override
	public void shutdown() {
		boolean shutdownChildren = false;
		synchronized (shutdown) {
			if (shutdown == false)
				shutdownChildren = true;
			shutdown = true;
		}
		if (shutdownChildren) {
			// LOW priority, could spawn a _lot_ of threads here, because they
			// mostly wait
			// would improve shutdown performance of large cluster...
			computeResources.stream().parallel().forEach((es) -> {
				es.shutdown();
			});
		}
		if (slt != null)
			slt.shutdown();
	}

	@Override
	public List<Runnable> shutdownNow() {
		synchronized (shuttingDownNow) {
			if (shuttingDownNow = true)
				throw new RuntimeException("Can't do two shutdownNows simultaneously");
			shuttingDownNow = true;
		}

		ConcurrentHashMap<Runnable, ExecutorService> unfinishedTasks = new ConcurrentHashMap<>();

		computeResources.stream().parallel().forEach((es) -> {
			List<Runnable> unfinished = es.shutdownNow();
			for (Runnable r : unfinished)
				unfinishedTasks.put(r, es);
		});

		List<Runnable> l = unfinishedTasks.keySet().stream().collect(Collectors.toList());
		terminated = true;
		return l;
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		for (ExecutorService es : computeResources)
			if (!es.isTerminated())
				return false;
		return true;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		// TODO Auto-generated method stub
		long waitInterval = 10;
		long maxMS = unit.toMillis(timeout);
		long totalWaited = 0;

		if (waitInterval > maxMS)
			waitInterval = maxMS;

		while (!isTerminated()) {
			Thread.sleep(waitInterval);
			totalWaited += waitInterval;
			if (totalWaited >= maxMS)
				break;
		}

		return isTerminated();
	}

	@Override
	public void execute(Runnable command) {
		synchronized (shutdown) {
			if (shutdown)
				throw new RejectedExecutionException();
		}
		if (command == null)
			throw new NullPointerException();

		if (!(command instanceof Serializable)) { // must be executed locally
			lge.submit(command);
			return;
		} else if (command instanceof ProducesRemoteResult) { 
			// can execute remotely or locally
			submitAnywhere(command);
		}

	}

	@Override
	protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
		// TODO Auto-generated method stub
		if (runnable instanceof RemoteCallable<?>) {
			RemoteCallable<T> rc = (RemoteCallable<T>) runnable;
			return rc;
		} else if (runnable instanceof RemoteRunnable) {
			RemoteRunnable<T> rr = (RemoteRunnable<T>) runnable;
			return rr;
		} else if (runnable instanceof Serializable) {
			try {
				RemoteRunnable<T> rr = new RemoteRunnable<>(runnable,value);
				return rr;
			} catch (NotSerializableException e) {
				// SHOULD BE IMPOSSIBLE
				e.printStackTrace();
			}
		}
		
		return super.newTaskFor(runnable, value);
	}
	
	

	@Override
	protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
		if (callable instanceof Serializable) {
			RemoteCallable<T> rc = new RemoteCallable<>(callable,null);
			return rc;
		}
		return super.newTaskFor(callable);
	}

	private NonBlockingExecutorService getNextComputeResource() {
		if (computePos >= computeResources.size())
			computePos = 0;
		NonBlockingExecutorService toReturn = computeResources.get(computePos);
		computePos++;
		return toReturn;
	}

	boolean submitAnywhere(Runnable r) {
		boolean success = false;

		while (!success) {
			int placesTried = 0;

			// try everyone once per cycle
			while (placesTried < computeResources.size()) {
				NonBlockingExecutorService nbes = getNextComputeResource();
				boolean foo = nbes.offer(r);
				if (foo) {
					return true;
				}
				placesTried++;
			}
			// Everyone was full, wait before trying again
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				return false;
			}
		}
		return false;
	}

}
