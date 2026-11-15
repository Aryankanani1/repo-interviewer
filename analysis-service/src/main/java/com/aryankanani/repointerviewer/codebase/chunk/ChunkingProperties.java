package com.aryankanani.repointerviewer.codebase.chunk;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.chunking}: how {@link JavaChunker} and {@link TextChunker} cut files into chunks. */
@Validated
@ConfigurationProperties(prefix = "app.chunking", ignoreUnknownFields = false)
public record ChunkingProperties(@Positive int maxChunkLines, @Positive int smallMethodLines) {
}
