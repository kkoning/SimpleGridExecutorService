package sres;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * RemoteRunnables can be sent to be executed in external JVMs. They are
 * intended to perform some (relatively expensive) computation, such as the
 * execution of an agent model, or a simulation to determine the fitness of a
 * candidate solution.
 * 
 * This means that they must (1) be {@link Serializable}, and (2) they must have
 * a way to preserve object identity so that results can be processed, e.g., as
 * a result in some larger computation. By default, RemoteRunnables are assigned
 * a random UUID on object creation. In addition, for job scheduling purposes, a
 * RemoteRunnable may specify an estimated time necessary to perform the
 * computation.
 * 
 * @author kkoning
 *
 */
@SuppressWarnings("serial")
public class RemoteCallable<T> implements Serializable, RemoteRunnableFuture<T>, ProducesRemoteResult {

	UUID id = UUID.randomUUID();
	Long estimatedTime = null;

	Callable<T> callable;
	T result = null;
	Exception e = null;

	transient Consumer<T> processResult;
	transient BlockingQueue<Optional<T>> rq = new ArrayBlockingQueue<>(1);
	boolean informedComplete = false;

	public RemoteCallable(Callable<T> callable, Consumer<T> processResult) {
		this.callable = callable;
		this.processResult = processResult;
	}

	@Override
	public void run() {
		try {
			result = callable.call();
		} catch (Exception e) {
			this.e = e;
		}
	}

	/**
	 * Processing of the result cannot happen directly from an object of this
	 * class, as it must be serialized and executed remotely. This breaks object
	 * identity, so, after the results have been computed, they must be sent
	 * back to the requesting process, matched with this object (through the
	 * UUIDs) and passed back into this function as a parameter. For the same
	 * reason, the Consumer<T> function/closure passed in the constructor is
	 * transient and not sent to the remote executor.
	 * 
	 * @param result
	 */
	@SuppressWarnings("unchecked") // Accounted for, should never happen
	public void processResponse(RemoteResult result) {
		Object o = result.getResult();
		T res;
		try {
			res = (T) o;
		} catch (ClassCastException cce) {
			throw new RuntimeException("Class of RemoteResult doesn't match expectations. "
					+ "Did a different result get mapped to us somehow?", cce);
		}
		if (processResult != null)
			processResult.accept(res);
		
		informedComplete = true;
		rq.add(Optional.ofNullable(res)); // unblock get
	}

	@Override
	public boolean isDone() {
		return informedComplete;
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		return rq.take().get();
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		return rq.poll(timeout, unit).get();
	}

	@Override
	public Object getResult() {
		return result;
	}

	@Override
	public Exception getException() {
		return e;
	}

	@Override
	public UUID getID() {
		return id;
	}

	public Optional<Long> estimatedTime() {
		return Optional.ofNullable(estimatedTime);
	}



}
