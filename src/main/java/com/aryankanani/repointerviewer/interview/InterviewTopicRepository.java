package com.aryankanani.repointerviewer.interview;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InterviewTopicRepository extends JpaRepository<InterviewTopic, UUID> {

	List<InterviewTopic> findBySessionIdOrderByOrderIndex(UUID sessionId);

}
