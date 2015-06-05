package sres;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestRemoteCallable {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	class Foo implements Callable<String> {
		@Override
		public String call() throws Exception {
			return "Hello World";
		}
	}

	@Test
	public void localTest() {

		RemoteCallable<String> rc = new EchoCommand("Echo Test", (s) -> System.out.println("Result was " + s));
		rc.run();
		RemoteResult rr = rc.getRemoteResult();
		rc.processResponse(rr);
	}

	@Test
	public void demo() throws InterruptedException {
		
		// Create the service.  This version does not accept remote connections
		GridExecutorService ges = new GridExecutorService();
		
		// A trivial RemoteCallable.  Typically there would be a computationally intensive
		// callable rather than a trivial closure returning "Hello World"
		RemoteCallable rc = new RemoteCallable(() -> "Hello World", (s) -> System.out.println(s));
		
		// Submit the task to be executed
		ges.submit(rc);

		// A larger task, demonstrating the need to await termination.
		CheckNumbersForPrimes cnfp = new CheckNumbersForPrimes(100_000, (n) -> System.out.println(n + " primes found"));
		ges.execute(cnfp);
		
		// shut down and await termination
		ges.shutdown();
		ges.awaitTermination(1, TimeUnit.MINUTES);
		
	}

}
