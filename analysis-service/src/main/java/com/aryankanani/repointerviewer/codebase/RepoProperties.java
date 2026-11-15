package com.aryankanani.repointerviewer.codebase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.repo}: which repositories may be analyzed, how big they may be, how long cloning may take, and where the
 * clones go (analysis-service). Validated at startup; a misspelled key fails ({@code ignoreUnknownFields = false}).
 */
@Validated
@ConfigurationProperties(prefix = "app.repo", ignoreUnknownFields = false)
public record RepoProperties(
		@NotBlank String allowedHost,
		@Positive int maxFiles,
		@Positive long maxFileBytes,
		@Positive long maxRepoBytes,
		@NotNull @DurationMin(seconds = 1) Duration lsRemoteTimeout,
		@NotNull @DurationMin(seconds = 1) Duration cloneTimeout,
		@NotNull Path workDir) {
}
