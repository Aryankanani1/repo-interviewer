package com.aryankanani.repointerviewer.interview;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

	/** Newest first. Served by the index {@code ix_session_project (project_id, started_at DESC)}. */
	List<InterviewSession> findByProjectIdOrderByStartedAtDesc(UUID projectId);

}
