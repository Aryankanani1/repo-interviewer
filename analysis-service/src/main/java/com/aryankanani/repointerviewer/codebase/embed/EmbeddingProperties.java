package com.aryankanani.repointerviewer.codebase.embed;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.embedding}: how many chunks {@link EmbeddingService} embeds per call, and the vector size, which must
 * match the {@code vector(768)} column of analysis-service's V1__init.sql.
 */
@Validated
@ConfigurationProperties(prefix = "app.embedding", ignoreUnknownFields = false)
public record EmbeddingProperties(@Positive int batchSize, @Positive int dimensions) {
}
