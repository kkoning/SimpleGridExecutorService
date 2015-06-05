package sres;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

public class RemoteResult implements Serializable {
	private static final long serialVersionUID = 4866692809860518902L;

	private UUID id;
	
	private Object result;
	private Exception exception;

	public RemoteResult(ProducesRemoteResult prr) {
		this.id = prr.getID();
		this.result = prr.getResult();
		this.exception = prr.getException();
	}
	
	Object getResult() {
		return result;
	}
	
	public void setResult(Object result) {
		this.result = result;
	}

	public Optional<Exception> getException() {
		return Optional.ofNullable(exception);
	}

	public void setException(Exception exception) {
		this.exception = exception;
	}

	public UUID getID() {
		return id;
	}
	
}
