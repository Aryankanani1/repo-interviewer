package com.aryankanani.repointerviewer.api.hardening;

import graphql.ExecutionInput;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import graphql.execution.preparsed.persisted.PersistedQuerySupport;
import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Trusted documents (task 4.6, DECISIONS.md D-033): the operations a client may run are files in this repository,
 * {@value #LOCATION}, one operation each. A request names one in two ways:
 * <ul>
 * <li>by its text, as usual. Compared after printing both compactly, so spacing and comments do not matter;</li>
 * <li>by the SHA-256 of its file alone, Apollo's way: {@code "extensions": {"persistedQuery": {"sha256Hash":
 * "<hex>"}}} and no {@code query}. The hex is what {@code shasum -a 256 <file>} prints.</li>
 * </ul>
 * With {@code app.graphql.trusted-documents-only} (prod) any other text is refused before it is even validated, so a
 * stolen token cannot explore the schema or write its own queries. Without it (dev, test, stage) any text runs, and
 * the hashes work too.
 *
 * <p>graphql-java asks this provider for each request's document; {@code parseAndValidate} is its own parsing and
 * validation, given the text to use.
 */
final class TrustedDocuments implements PreparsedDocumentProvider {

	static final String LOCATION = "classpath:graphql-documents/*.graphql";

	/** The SHA-256 of each file, in lower-case hex, to its text. */
	private final Map<String, String> byHash;

	/** Each document printed compactly: the same operation gives the same text, whatever its spacing. */
	private final Set<String> compactTexts;

	private final boolean only;

	TrustedDocuments(Map<String, String> byHash, boolean only) {
		this.byHash = Map.copyOf(byHash);
		this.compactTexts = byHash.values().stream().map(TrustedDocuments::compact).collect(Collectors.toSet());
		this.only = only;
	}

	/** Reads the list; a file that is not one operation stops the startup, naming the file. */
	static TrustedDocuments load(boolean only) {
		Map<String, String> byHash = new LinkedHashMap<>();
		try {
			for (Resource file : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
				String text = file.getContentAsString(StandardCharsets.UTF_8);
				if (operations(text) != 1) {
					throw new IllegalStateException("Trusted document " + file.getFilename()
							+ " must be exactly one GraphQL operation.");
				}
				byHash.put(sha256(text), text);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new TrustedDocuments(byHash, only);
	}

	/**
	 * A request that names a listed document by its hash runs that document, whatever text came with it. Any other
	 * request runs its own text, which with {@code only} must be on the list.
	 */
	@Override
	public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(ExecutionInput input,
			Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidate) {
		String hash = requestedHash(input);
		String listed = hash == null ? null : byHash.get(hash);
		if (listed != null) {
			return CompletableFuture.completedFuture(parseAndValidate.apply(input.transform(next -> next.query(listed))));
		}
		String text = input.getQuery();
		if (PersistedQuerySupport.PERSISTED_QUERY_MARKER.equals(text)) {   // Spring's stand-in for "no query, a hash"
			return refused("No document on this API's list has that sha256Hash.");
		}
		if (only && !compactTexts.contains(compact(text))) {
			return refused("Only the operations on this API's list of trusted documents can run here.");
		}
		return CompletableFuture.completedFuture(parseAndValidate.apply(input));
	}

	/** {@code extensions.persistedQuery.sha256Hash}, or null. */
	private static String requestedHash(ExecutionInput input) {
		if (input.getExtensions().get("persistedQuery") instanceof Map<?, ?> persisted
				&& persisted.get("sha256Hash") instanceof String hash) {
			return hash.toLowerCase(Locale.ROOT);
		}
		return null;
	}

	/** 0 when it is not GraphQL at all. */
	private static int operations(String text) {
		try {
			return Parser.parse(text).getDefinitionsOfType(OperationDefinition.class).size();
		}
		catch (InvalidSyntaxException ex) {
			return 0;
		}
	}

	/** Null when it is not GraphQL at all, so it matches nothing. */
	private static String compact(String text) {
		try {
			return AstPrinter.printAstCompact(Parser.parse(text));
		}
		catch (InvalidSyntaxException ex) {
			return null;
		}
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java runtime has SHA-256", ex);
		}
	}

	private static CompletableFuture<PreparsedDocumentEntry> refused(String message) {
		return CompletableFuture.completedFuture(new PreparsedDocumentEntry(InvalidQuery.error(message)));
	}

}
