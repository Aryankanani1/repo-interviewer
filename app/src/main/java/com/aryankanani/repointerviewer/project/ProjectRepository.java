package com.aryankanani.repointerviewer.project;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProjectRepository extends JpaRepository<Project, UUID> {

	/**
	 * One page of projects, newest first, using keyset scrolling.
	 *
	 * <p>The sort is fixed in the method name on purpose: it must match the index
	 * {@code ix_project_created_at (created_at DESC, id DESC)}, and {@code id} breaks ties between
	 * projects created at the same moment, so no row is skipped or repeated between pages.
	 * With a keyset position, Spring Data adds {@code WHERE (created_at, id) < (last seen values)}
	 * instead of {@code OFFSET n}.
	 */
	Window<Project> findAllByOrderByCreatedAtDescIdDesc(ScrollPosition position, Limit limit);

	/** Duplicate check for submitProject. Backed by the unique constraint uq_project_url_commit. */
	Optional<Project> findByGithubUrlAndCommitSha(String githubUrl, String commitSha);

	/**
	 * Marks analyses that stopped moving as FAILED, in one statement ({@link StaleAnalysisRecovery}). A bulk
	 * UPDATE skips Hibernate, so it sets {@code updated_at} and bumps {@code version} itself.
	 */
	@Modifying
	@Query(value = """
			UPDATE project
			SET status = 'FAILED', failure_reason = :reason, updated_at = now(), version = version + 1
			WHERE status IN ('CLONING', 'PARSING', 'EMBEDDING', 'SUMMARIZING') AND updated_at < :cutoff
			""", nativeQuery = true)
	int failStale(@Param("cutoff") Instant cutoff, @Param("reason") String reason);

}
