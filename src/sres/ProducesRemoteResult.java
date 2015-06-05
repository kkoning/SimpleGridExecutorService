package sres;

import java.util.UUID;

public interface ProducesRemoteResult extends Runnable {
	default RemoteResult getRemoteResult() {
		return new RemoteResult(this);
	}
	Object getResult();
	Exception getException();
	UUID getID();
}
