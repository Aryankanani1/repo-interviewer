package com.aryankanani.repointerviewer.api.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.persisted.PersistedQuerySupport;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Task 4.6: the list of trusted documents, and which document each kind of request runs. No Spring: graphql-java's
 * parsing and validation is replaced by a function that records the text it was given, so a refused request is one
 * that never got there.
 */
class TrustedDocumentsTest {

	private static final String PROJECTS = "graphql-documents/Projects.graphql";

	/** The texts that reached graphql-java's parsing and validation. */
	private final List<String> parsed = new ArrayList<>();

	/** What CI runs: a typo in a document would make that operation fail for every client in prod. */
	@Test
	void everyDocumentIsOneNamedOperationTheSchemaAccepts() throws Exception {
		GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
				new SchemaParser().parse(read(new ClassPathResource("graphql/schema.graphqls"))));
		Resource[] files = new PathMatchingResourcePatternResolver().getResources(TrustedDocuments.LOCATION);

		assertThat(files).hasSizeGreaterThanOrEqualTo(10);
		for (Resource file : files) {
			Document document = Parser.parse(read(file));
			assertThat(document.getDefinitionsOfType(OperationDefinition.class)).as(file.getFilename())
					.singleElement().extracting(OperationDefinition::getName)
					.isEqualTo(file.getFilename().replace(".graphql", ""));
			assertThat(ParseAndValidate.validate(schema, document)).as(file.getFilename()).isEmpty();
		}
	}

	@Test
	void aListedDocumentRunsAsTextWhateverItsSpacingAndComments() throws Exception {
		String reformatted = "# from a client's own copy\n" + read(new ClassPathResource(PROJECTS))
				.replace("\n", "\n\n   ").replace("{ ", "{");

		assertThat(documentFor(TrustedDocuments.load(true), reformatted, Map.of()).hasErrors()).isFalse();
		assertThat(parsed).containsExactly(reformatted);
	}

	/** Apollo's way, and what Spring makes of a request with no {@code query}: the text comes from the list. */
	@Test
	void aListedDocumentRunsByTheSha256OfItsFileAlone() throws Exception {
		String file = read(new ClassPathResource(PROJECTS));

		PreparsedDocumentEntry entry = documentFor(TrustedDocuments.load(true),
				PersistedQuerySupport.PERSISTED_QUERY_MARKER, persistedQuery(sha256(file)));

		assertThat(entry.hasErrors()).isFalse();
		assertThat(parsed).containsExactly(file);
	}

	@Test
	void withTrustedDocumentsOnlyAnyOtherTextIsRefusedBeforeItIsValidated() {
		TrustedDocuments prod = TrustedDocuments.load(true);

		for (String text : List.of("{ projects(first: 100) { edges { node { id } } } }",
				"{ __schema { types { name } } }", "not GraphQL at all")) {
			assertRefused(documentFor(prod, text, Map.of()),
					"Only the operations on this API's list of trusted documents can run here.");
		}
		assertThat(parsed).isEmpty();
	}

	@Test
	void anUnknownHashWithoutTextIsRefusedInEveryEnvironment() {
		for (boolean only : List.of(true, false)) {
			assertRefused(documentFor(TrustedDocuments.load(only), PersistedQuerySupport.PERSISTED_QUERY_MARKER,
					persistedQuery(sha256(UUID.randomUUID().toString()))),
					"No document on this API's list has that sha256Hash.");
		}
		assertThat(parsed).isEmpty();
	}

	/** Dev, test and stage: anything runs, and an unknown hash that came with its text runs that text. */
	@Test
	void withoutTrustedDocumentsOnlyAnyTextRuns() {
		TrustedDocuments dev = TrustedDocuments.load(false);
		String query = "{ projects(first: 100) { edges { node { id } } } }";

		assertThat(documentFor(dev, query, Map.of()).hasErrors()).isFalse();
		assertThat(documentFor(dev, query, persistedQuery(sha256(query))).hasErrors()).isFalse();
		assertThat(parsed).containsExactly(query, query);
	}

	private PreparsedDocumentEntry documentFor(TrustedDocuments documents, String query,
			Map<String, Object> extensions) {
		ExecutionInput input = ExecutionInput.newExecutionInput(query).extensions(extensions).build();
		return documents.getDocumentAsync(input, given -> {
			parsed.add(given.getQuery());
			return new PreparsedDocumentEntry(Parser.parse(given.getQuery()));
		}).join();
	}

	private static void assertRefused(PreparsedDocumentEntry entry, String message) {
		assertThat(entry.getErrors()).singleElement().satisfies(error -> {
			assertThat(error.getMessage()).isEqualTo(message);
			assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT");
		}).extracting(GraphQLError::getErrorType).hasToString("BAD_REQUEST");
	}

	private static Map<String, Object> persistedQuery(String sha256Hash) {
		return Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", sha256Hash));
	}

	/** What {@code shasum -a 256 <file>} prints. */
	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String read(Resource resource) throws IOException {
		return resource.getContentAsString(StandardCharsets.UTF_8);
	}

}
