package com.aryankanani.repointerviewer.api.hardening;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.graphql} (task 4.6, DECISIONS.md D-033): what a single GraphQL request may ask for. Validated at startup;
 * a misspelled key fails it (task 4.1).
 *
 * @param maxDepth the most levels of nested fields: {@code { project { sessions { id } } } } is 3
 * @param maxComplexity the most fields in one request, counting every field at every level (an alias counts again)
 * @param trustedDocumentsOnly run only the operations on the list ({@link TrustedDocuments}); on in prod only
 */
@Validated
@ConfigurationProperties(prefix = "app.graphql", ignoreUnknownFields = false)
public record GraphQlHardeningProperties(@Min(1) int maxDepth, @Min(1) int maxComplexity,
		boolean trustedDocumentsOnly) {
}
