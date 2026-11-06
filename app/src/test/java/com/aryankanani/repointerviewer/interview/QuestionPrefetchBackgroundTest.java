package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static com.aryankanani.repointerviewer.interview.QuestionPrefetcherTest.ORDER;
import static com.aryankanani.repointerviewer.interview.QuestionPrefetcherTest.PREFETCHED;
import static com.aryankanani.repointerviewer.interview.QuestionPrefetcherTest.question;
import static com.aryankanani.repointerviewer.interview.QuestionPrefetcherTest.secondTopicQuestion;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 1.16: with {@code app.interview.prefetch-enabled} on, {@code startInterview} starts the prefetch in the
 * background and returns without waiting for it. Its own class because the flag needs its own Spring context; the
 * rest is in {@link QuestionPrefetcherTest}.
 */
@IntegrationTest
@TestPropertySource(properties = "app.interview.prefetch-enabled=true")
class QuestionPrefetchBackgroundTest {

	private final InterviewService interviewService;

	private final InterviewTopicRepository topics;

	private final QuestionRepository questions;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	QuestionPrefetchBackgroundTest(@Autowired InterviewService interviewService,
			@Autowired InterviewTopicRepository topics, @Autowired QuestionRepository questions,
			@Autowired FixtureIndexer fixture, @Autowired FakeLlmGateway llm,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.interviewService = interviewService;
		this.topics = topics;
		this.questions = questions;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@Test
	void startInterviewPrefetchesTheSecondTopicsQuestionInTheBackground() {
		jdbc.update("DELETE FROM project");
		llm.reset();
		embeddingModel.reset();
		UUID project = fixture.readyProject();
		llm.enqueue(LlmPurpose.QUESTION, question("Why does OrderController#refund take a RefundRequest body?",
				ORDER + "OrderController.java", 27, 30));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());   // taken by the background prefetch

		StartResult started = interviewService.start(new StartInterviewCommand(project, null, null, null));

		InterviewTopic secondTopic = topics.findBySessionIdOrderByOrderIndex(started.session().getId()).get(1);
		await().atMost(Duration.ofSeconds(10))
				.until(() -> questions.findByTopicIdAndFollowUpFalse(secondTopic.getId()).isPresent());
		Question prepared = questions.findByTopicIdAndFollowUpFalse(secondTopic.getId()).orElseThrow();
		assertThat(prepared.getText()).isEqualTo(PREFETCHED);
		assertThat(prepared.getStatus()).isEqualTo(QuestionStatus.PREPARED);
		assertThat(started.firstQuestion().getOrderIndex()).isEqualTo(1);
		assertThat(interviewService.askedQuestions(started.session().getId())).hasSize(1);
	}

}
