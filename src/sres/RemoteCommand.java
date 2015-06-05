package sres;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

public abstract class RemoteCommand<T> extends RemoteCallable<T> implements Callable<T> {
	private static final long serialVersionUID = 8438629481198346529L;

	RemoteEvaluationWorker rew;
	
	public RemoteCommand(Consumer<T> processResult) {
		super(null, processResult);
		super.callable = this;
	}

	public void setRemoteEvaluationWorker(RemoteEvaluationWorker rew) {
		this.rew = rew;
	}
	
}
