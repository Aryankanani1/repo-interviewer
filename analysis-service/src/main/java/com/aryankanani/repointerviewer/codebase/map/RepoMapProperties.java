package com.aryankanani.repointerviewer.codebase.map;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.repo-map}: the token budget of the repo map {@link RepoMapBuilder} writes for prompts. */
@Validated
@ConfigurationProperties(prefix = "app.repo-map", ignoreUnknownFields = false)
public record RepoMapProperties(@Positive int maxTokens) {
}
