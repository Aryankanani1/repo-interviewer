package com.aryankanani.repointerviewer.interview;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionRepository extends JpaRepository<Question, UUID> {

	/** The questions the candidate has seen, in order. PREPARED ones have no order yet and stay hidden. */
	List<Question> findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(UUID sessionId);

	/** The asked questions of several sessions in one query (GraphQL batch loading). */
	List<Question> findBySessionIdInAndOrderIndexNotNullOrderByOrderIndex(Collection<UUID> sessionIds);

	/** Backed by the unique constraint {@code uq_question_order (session_id, order_index)}. */
	Optional<Question> findBySessionIdAndOrderIndex(UUID sessionId, Integer orderIndex);

}
