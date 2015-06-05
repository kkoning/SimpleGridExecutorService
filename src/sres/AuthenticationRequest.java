package sres;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

public class AuthenticationRequest implements Serializable {
	private static final long serialVersionUID = 8105489949108167573L;

	UUID salt = UUID.randomUUID();
	byte[] result;

	public void calculateResult(String secret) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String concat = salt + secret;
			result = md.digest(concat.getBytes());
		} catch (NoSuchAlgorithmException e) {
			// Do nothing, will just fail authentication
		}
	}
	
	public boolean checkResult(String secret) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String concat = salt + secret;
			byte[] target = md.digest(concat.getBytes());
			
			return Arrays.equals(target, result);
		} catch (NoSuchAlgorithmException e) {
			// Do nothing, will just fail authentication
		}
		
		return false;
	}
	
}
