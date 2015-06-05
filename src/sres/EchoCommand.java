package sres;

import java.util.function.Consumer;

/**
 * Simply echo the provided message to the remote console. Useful for testing
 * and debugging purposes.
 * 
 * @author kkoning
 *
 */
public class EchoCommand extends RemoteCommand<String> {
	private static final long serialVersionUID = 6893567300676820872L;
	private String message;
	
	public EchoCommand(String message, Consumer<String> processResult) {
		super(processResult);
		this.message = message;
	}
	
	@Override
	public String call() throws Exception {
		System.out.println(message);
		return message;
	}

}
