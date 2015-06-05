package sres;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GridExecutorTest {
	private static final String secret = "secret";
	
	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {

	}

	@Test
	public void testLocal() throws Exception {
		GridExecutorService ges = new GridExecutorService();
		ges.execute(() -> {
			System.out.println("Hello World");
		});

		ges.execute(new EchoCommand("Local Echo", (s) -> System.out.println("Returned " + s)));
		
		for (int i = 0; i < 60; i++) {
			CheckNumbersForPrimes cnfp = new CheckNumbersForPrimes(100_000, (n) -> System.out.println(n + " primes found"));
			ges.execute(cnfp);
			System.out.println("CNFP submitted " + i + " times");
		}
		
		System.out.println("Shutting down");
		ges.shutdown();
		System.out.println("Shut down, awaiting termination");
		ges.awaitTermination(1, TimeUnit.HOURS);
	}
	
	
	@Test
	public void testRemote() throws Exception {
		GridExecutorService ges = new GridExecutorService(54321, secret, false, Optional.empty());
		Thread.sleep(10); // give time for socket setup
		RemoteEvaluationWorker rew = new RemoteEvaluationWorker("localhost", 54321, secret);
		Thread.sleep(50); // give time for connection setup
		
		assert(ges.hasRemotes());
		EchoCommand ec = new EchoCommand("Remote Echo", (s) -> System.out.println("Returned " + s));
		
		ges.execute(ec);
		
		for (int i = 0; i < 60; i++) {
			CheckNumbersForPrimes cnfp = new CheckNumbersForPrimes(100_000, (n) -> System.out.println(n + " primes found"));
			ges.execute(cnfp);
			System.out.println("CNFP submitted " + i + " times");
		}
		
		System.out.println("Shutting down");
		ges.shutdown();
		System.out.println("Shut down, awaiting termination");
		ges.awaitTermination(1, TimeUnit.HOURS);
		
	}
	
	@Test
	public void testBoth() throws Exception {
		GridExecutorService ges = new GridExecutorService(54321, secret, true, Optional.empty());
		Thread.sleep(10); // give time for socket setup
		RemoteEvaluationWorker rew = new RemoteEvaluationWorker("localhost", 54321, secret);
		Thread.sleep(50); // give time for connection setup
		
		assert(ges.hasRemotes());
		EchoCommand ec = new EchoCommand("Either Echo", (s) -> System.out.println("Returned " + s));
		
		ges.execute(ec);

		for (int i = 0; i < 60; i++) {
			CheckNumbersForPrimes cnfp = new CheckNumbersForPrimes(100_000, (n) -> System.out.println(n + " primes found"));
			ges.execute(cnfp);
			System.out.println("CNFP submitted " + i + " times");
		}
		
		System.out.println("Shutting down");
		ges.shutdown();
		System.out.println("Shut down, awaiting termination");
		ges.awaitTermination(1, TimeUnit.HOURS);
		
	}
	


}
