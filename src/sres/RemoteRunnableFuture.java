package sres;

import java.util.concurrent.RunnableFuture;

public interface RemoteRunnableFuture<T> extends RunnableFuture<T> {
	void processResponse(RemoteResult result);
	public default boolean cancel(boolean mayInterruptIfRunning) {
		// TODO Implement canceling
		return false;
	}

	public default boolean isCancelled() {
		// TODO Implement canceling
		return false;
	}

}
