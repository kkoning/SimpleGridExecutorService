package sres;

import java.util.concurrent.ExecutorService;

public interface NonBlockingExecutorService extends ExecutorService {
	boolean offer(Runnable r);
}
