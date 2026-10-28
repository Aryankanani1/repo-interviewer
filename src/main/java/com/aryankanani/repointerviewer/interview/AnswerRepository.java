package com.aryankanani.repointerviewer.interview;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AnswerRepository extends JpaRepository<Answer, UUID> {

	/** Backed by the unique constraint {@code uq_answer_question}: at most one answer per question. */
	Optional<Answer> findByQuestionId(UUID questionId);

	/**
	 * {@code SELECT ... FOR UPDATE}: a second request saving the same evaluation waits for the first one's short
	 * transaction, then sees it is already evaluated. Never held across a model call.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Answer a where a.id = :id")
	Optional<Answer> findByIdForUpdate(@Param("id") UUID id);

}
