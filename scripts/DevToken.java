import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A JWT for local use (task 4.4, README "Tokens for local use"), signed RS256 with your own dev key, which the
 * gateway's dev profile checks with the matching public key. Only the JDK: run it as a single-file program.
 *
 * <pre>
 * mkdir -p .dev-keys
 * openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .dev-keys/jwt-private.pem
 * openssl pkey -in .dev-keys/jwt-private.pem -pubout -out .dev-keys/jwt-public.pem
 * java scripts/DevToken.java                 # user "local-dev", valid 8 hours
 * java scripts/DevToken.java alice 30        # user "alice", valid 30 minutes
 * </pre>
 *
 * Projects and sessions from before task 4.4 belong to "local-dev". Never use a dev key outside your machine.
 */
public class DevToken {

	public static void main(String[] args) throws Exception {
		String user = args.length > 0 ? args[0] : "local-dev";
		long minutes = args.length > 1 ? Long.parseLong(args[1]) : 8 * 60;
		if (!user.matches("[A-Za-z0-9._@-]{1,100}")) {
			throw new IllegalArgumentException("A user id is 1-100 letters, digits or . _ @ -");
		}
		Instant now = Instant.now();
		Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
		String header = base64url.encodeToString(
				"{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String claims = base64url.encodeToString("{\"sub\":\"%s\",\"iat\":%d,\"exp\":%d}".formatted(user,
				now.getEpochSecond(), now.plus(Duration.ofMinutes(minutes)).getEpochSecond())
				.getBytes(StandardCharsets.UTF_8));

		Signature rsa = Signature.getInstance("SHA256withRSA");
		rsa.initSign(privateKey(Path.of(".dev-keys", "jwt-private.pem")));
		rsa.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
		System.out.println(header + "." + claims + "." + base64url.encodeToString(rsa.sign()));
	}

	/** A PKCS#8 PEM file ("BEGIN PRIVATE KEY"), what {@code openssl genpkey} writes. */
	private static PrivateKey privateKey(Path pem) throws Exception {
		String base64 = Files.readString(pem).replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
		byte[] pkcs8 = Base64.getDecoder().decode(base64);
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
	}

}
