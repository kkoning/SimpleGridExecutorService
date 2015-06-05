package sres;

import java.util.function.Consumer;

public class ShutdownCommand extends RemoteCommand<Boolean> {
	private static final long serialVersionUID = 7030179082294182779L;

	public ShutdownCommand(Consumer<Boolean> processResult) {
		super(processResult);
	}

	@Override
	public Boolean call() throws Exception {
		rew.shutdown();
		return true;
	}

}
