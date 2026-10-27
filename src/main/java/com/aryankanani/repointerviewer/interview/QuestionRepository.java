package com.aryankanani.repointerviewer.interview;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionRepository extends JpaRepository<Question, UUID> {

	/** The questions the candidate has seen, in order. PREPARED ones have no order yet and stay hidden. */
	List<Question> findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(UUID sessionId);

}
