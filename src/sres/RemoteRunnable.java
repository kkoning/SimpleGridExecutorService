package sres;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class RemoteRunnable<T> implements RemoteRunnableFuture<T>, ProducesRemoteResult, Serializable {
	private static final long serialVersionUID = 6397780324469033052L;

	private UUID id = UUID.randomUUID();
	private Runnable runnable;
	private Exception exception = null;
	
	private BlockingQueue<Optional<T>> rq = new ArrayBlockingQueue<>(1);
	T res = null;
	boolean informedComplete = false;
	
	
	public RemoteRunnable(Runnable r) throws NotSerializableException {
		if (r instanceof Serializable) {
			runnable = r;
		} else {
			throw new NotSerializableException();
		}
	}

	public RemoteRunnable(Runnable r, T res) throws NotSerializableException {
		this(r);
		this.res = res;
	}

	
	@Override
	public void run() {
		try {
			runnable.run();
		} catch (Exception e) {
			this.exception = e;
		}
	}
	
	@Override
	public void processResponse(RemoteResult result) {
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
		return null;
	}

	@Override
	public Exception getException() {
		return exception;
	}

	@Override
	public UUID getID() {
		return id;
	}
}
