package sres;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CheckNumbersForPrimes extends RemoteCommand<Integer> {
	private static final long serialVersionUID = 2421456648350238697L;
	List<Long> primes = new ArrayList<>();

	long limit = 0;
	
	public CheckNumbersForPrimes(long limit, Consumer<Integer> processResult) {
		super(processResult);
		this.limit = limit;
	}
	
	@Override
	public Integer call() throws Exception {
		primes.add(2l);
		primes.add(3l);
		primes.add(5l);
		primes.add(7l);
		primes.add(11l);
		for (long i = 13; i < limit; i++) {
			if (isPrime(i))
				primes.add(i);
		}
		return primes.size();
	}
	
	boolean isPrime(long number) {
		if (number <=1)
			return false;
		long highestPrimeTried = 2;
		long squareRoot = (long) Math.sqrt(number);
		for (Long prime : primes) {
			if ( (number % prime) == 0) 
				return false; // divisible by a prime
			if (highestPrimeTried < prime)
				highestPrimeTried = prime;
		}
		
		if (highestPrimeTried > squareRoot)
			return true;
		
		for (long i = highestPrimeTried+1; i < squareRoot; i++) {
			if ((number % i) == 0)
				return true;
		}
		
		return false;
	}
	
}