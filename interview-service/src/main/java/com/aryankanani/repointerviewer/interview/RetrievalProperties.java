package com.aryankanani.repointerviewer.interview;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.retrieval} in interview-service: how many tokens of code a prompt may carry, sent as the budget of each
 * chunk search (analysis-service's own {@code app.retrieval} says how many chunks it adds by meaning).
 */
@Validated
@ConfigurationProperties(prefix = "app.retrieval", ignoreUnknownFields = false)
public record RetrievalProperties(@Positive int maxContextTokens) {
}
