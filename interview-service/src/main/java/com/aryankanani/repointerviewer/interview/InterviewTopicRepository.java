package com.aryankanani.repointerviewer.interview;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InterviewTopicRepository extends JpaRepository<InterviewTopic, UUID> {

	List<InterviewTopic> findBySessionIdOrderByOrderIndex(UUID sessionId);

	/**
	 * {@code SELECT ... FOR UPDATE}: the prefetch and {@code submitAnswer} both lock the topic before saving its main
	 * question, so the second one waits, then sees the first one's question (task 1.16). Never held across a model
	 * call.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from InterviewTopic t where t.id = :id")
	Optional<InterviewTopic> findByIdForUpdate(@Param("id") UUID id);

}
