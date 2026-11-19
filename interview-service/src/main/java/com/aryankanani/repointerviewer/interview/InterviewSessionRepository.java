package com.aryankanani.repointerviewer.interview;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

	/**
	 * The sessions of several projects in one query (GraphQL batch loading), newest first. Served by the index
	 * {@code ix_session_project (project_id, started_at DESC)}.
	 */
	List<InterviewSession> findByProjectIdInOrderByStartedAtDesc(Collection<UUID> projectIds);

	/**
	 * Deletes a project's sessions (task 4.4). The database removes their topics, questions and answers
	 * ({@code ON DELETE CASCADE}) and keeps their model calls with no session ({@code ON DELETE SET NULL}).
	 */
	@Modifying
	@Query(value = "DELETE FROM interview_session WHERE project_id = :projectId", nativeQuery = true)
	int deleteByProject(@Param("projectId") UUID projectId);

}
