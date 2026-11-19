package com.aryankanani.repointerviewer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signed JWTs for the tests (task 4.4): one RSA key pair per test run, generated here, so no private key is ever
 * committed, and RS256 tokens signed with it using the JDK alone. The gateway's tests check them with
 * {@link #publicKey()}; the system test gives the gateway {@link #publicKeyPem()} as a file, as a deployment would.
 *
 * <p>A JWT is three base64url parts joined by dots: a header ({@code {"alg":"RS256","typ":"JWT"}}), the claims, and
 * the RSA signature of the first two. Whoever holds the private key can make tokens; the public key only checks them.
 */
public final class TestTokens {

	private static final KeyPair KEYS = generate();

	/** A key the gateway does not know: a token signed with it must be refused. */
	private static final KeyPair STRANGER = generate();

	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

	private TestTokens() {
	}

	/** A token for {@code userId}, valid for an hour. */
	public static String token(String userId) {
		return sign(userId, Instant.now().plus(Duration.ofHours(1)), KEYS.getPrivate());
	}

	/** A token for {@code userId} that expired five minutes ago, past the 60 s of clock skew a validator allows. */
	public static String expiredToken(String userId) {
		return sign(userId, Instant.now().minus(Duration.ofMinutes(5)), KEYS.getPrivate());
	}

	/** A well-formed token for {@code userId}, signed with a key the gateway does not trust. */
	public static String tokenSignedByAStranger(String userId) {
		return sign(userId, Instant.now().plus(Duration.ofHours(1)), STRANGER.getPrivate());
	}

	public static RSAPublicKey publicKey() {
		return (RSAPublicKey) KEYS.getPublic();
	}

	/** The public key as a PEM file holds it (X.509 SubjectPublicKeyInfo), the form the gateway reads. */
	public static String publicKeyPem() {
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
				.encodeToString(KEYS.getPublic().getEncoded());
		return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
	}

	private static String sign(String userId, Instant expiresAt, PrivateKey key) {
		Instant issuedAt = expiresAt.minus(Duration.ofHours(1));
		String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		String claims = encode("{\"sub\":\"%s\",\"iat\":%d,\"exp\":%d}".formatted(userId, issuedAt.getEpochSecond(),
				expiresAt.getEpochSecond()));
		try {
			Signature rsa = Signature.getInstance("SHA256withRSA");
			rsa.initSign(key);
			rsa.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
			return header + "." + claims + "." + BASE64URL.encodeToString(rsa.sign());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String encode(String json) {
		return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
