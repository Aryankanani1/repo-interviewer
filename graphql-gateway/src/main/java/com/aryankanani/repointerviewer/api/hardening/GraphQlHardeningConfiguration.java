package com.aryankanani.repointerviewer.api.hardening;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.Instrumentation;
import java.util.List;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GraphQL hardening (task 4.6, DECISIONS.md D-033). One request can ask for a lot: the schema has cycles
 * ({@code InterviewSession.project.sessions.project...}, {@code Question.parent}), and aliases repeat a field as often
 * as the client likes. Two limits from graphql-java refuse such a request after it is parsed and validated and before
 * any field runs, so no service is called; and {@link TrustedDocuments} decides which documents may run at all.
 *
 * <p>Boot hands every {@link Instrumentation} bean to the GraphQL engine, and each
 * {@link GraphQlSourceBuilderCustomizer} may change the engine's builder.
 */
@Configuration(proxyBeanMethods = false)
class GraphQlHardeningConfiguration {

	/** Levels of nested fields, counted by graphql-java: {@code { project { sessions { id } } } } is 3. */
	@Bean
	Instrumentation maxQueryDepth(GraphQlHardeningProperties settings) {
		return new MaxQueryDepthInstrumentation(settings.maxDepth()) {
			@Override
			protected AbortExecutionException mkAbortException(int depth, int maxDepth) {
				return refused("The query is too deep: " + depth + " levels of fields, at most " + maxDepth + ".");
			}
		};
	}

	/**
	 * graphql-java's default cost, 1 per field plus the cost of its sub-fields: the number of fields in the request.
	 * Lists are not multiplied by their size, so it counts what the client wrote, not what the data holds.
	 */
	@Bean
	Instrumentation maxQueryComplexity(GraphQlHardeningProperties settings) {
		return new MaxQueryComplexityInstrumentation(settings.maxComplexity()) {
			@Override
			protected AbortExecutionException mkAbortException(int complexity, int maxComplexity) {
				return refused("The query asks for too much: " + complexity + " fields, at most " + maxComplexity
						+ ".");
			}
		};
	}

	/** Every request's document comes from {@link TrustedDocuments} (a PreparsedDocumentProvider). */
	@Bean
	GraphQlSourceBuilderCustomizer trustedDocuments(GraphQlHardeningProperties settings) {
		TrustedDocuments documents = TrustedDocuments.load(settings.trustedDocumentsOnly());
		return builder -> builder.configureGraphQl(graphQl -> graphQl.preparsedDocumentProvider(documents));
	}

	/** graphql-java turns the exception into the response: these errors and no {@code data}. */
	private static AbortExecutionException refused(String message) {
		return new AbortExecutionException(List.of(InvalidQuery.error(message)));
	}

}
