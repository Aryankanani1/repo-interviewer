package com.aryankanani.repointerviewer.codebase.retrieve;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.retrieval} in analysis-service: how many chunks {@link Retriever} adds by meaning after the exact hits.
 * The token budget is the caller's, sent with each search (interview-service's own {@code app.retrieval}).
 */
@Validated
@ConfigurationProperties(prefix = "app.retrieval", ignoreUnknownFields = false)
public record RetrievalProperties(@Positive int topK) {
}
