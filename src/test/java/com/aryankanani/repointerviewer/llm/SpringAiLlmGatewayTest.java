package com.aryankanani.repointerviewer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 1.8 acceptance tests for {@link SpringAiLlmGateway}: the real gateway, budget and ledger on a real
 * database, with {@link StubChatModel} in place of Claude.
 */
@IntegrationTest
class SpringAiLlmGatewayTest {

	private static final String VALID_SUMMARY = """
			{"oneLiner": "  A small order service.  ", "architectureStyle": "layered monolith",
			 "techStack": ["Spring Boot", "PostgreSQL"],
			 "modules": [{"name": "order", "responsibility": "Orders and refunds", "keyClasses": ["OrderService"]}],
			 "notablePatterns": [], "riskAreas": null}
			""";

	private final PromptTemplates templates;

	private final LlmBudget budget;

	private final LlmCallLedger ledger;

	private final LlmCallRepository calls;

	private final AppProperties properties;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final StubChatModel claude = new StubChatModel();

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private SpringAiLlmGateway gateway;

	private UUID project;

	SpringAiLlmGatewayTest(@Autowired PromptTemplates templates, @Autowired LlmBudget budget,
			@Autowired LlmCallLedger ledger, @Autowired LlmCallRepository calls, @Autowired AppProperties properties,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc,
			@Autowired PlatformTransactionManager transactionManager) {
		this.templates = templates;
		this.budget = budget;
		this.ledger = ledger;
		this.calls = calls;
		this.properties = properties;
		this.fixture = fixture;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM llm_call");
		jdbc.update("DELETE FROM project");
		project = fixture.newProject();
		gateway = new SpringAiLlmGateway(ChatClient.builder(claude), templates, budget, ledger, properties, meters);
	}

	/** Acceptance criterion: a successful call writes one ledger row with its tokens. */
	@Test
	void successReturnsTheNormalizedOutputAndWritesOneLedgerRow() {
		claude.reply(VALID_SUMMARY);

		SummaryOutput summary = gateway.call(summaryRequest(null));

		assertThat(summary.oneLiner()).isEqualTo("A small order service.");   // trimmed
		assertThat(summary.riskAreas()).isEmpty();                              // null became empty
		LlmCall row = calls.findAll().getFirst();
		assertThat(calls.count()).isEqualTo(1);
		assertThat(row.getPurpose()).isEqualTo(LlmPurpose.SUMMARY);
		assertThat(row.getModel()).isEqualTo("claude-haiku-4-5");
		assertThat(row.getPromptVersion()).isEqualTo(templates.version("repo-summary"));
		assertThat(row.getProjectId()).isEqualTo(project);
		assertThat(row.getInputTokens()).isEqualTo(StubChatModel.INPUT_TOKENS);
		assertThat(row.getOutputTokens()).isEqualTo(StubChatModel.OUTPUT_TOKENS);
		assertThat(row.isSuccess()).isTrue();
		assertThat(row.getErrorType()).isNull();
		assertThat(row.getAttempt()).isEqualTo(1);
		assertThat(meters.get("ri.llm.call").tag("outcome", "success").tag("purpose", "SUMMARY").timer().count())
				.isEqualTo(1);
	}

	@Test
	void thePromptCarriesTheSystemMessageTheFilledTemplateAndTheSchema() {
		claude.reply(VALID_SUMMARY);

		gateway.call(summaryRequest(null));

		Prompt sent = claude.prompts().getFirst();
		assertThat(sent.getSystemMessage().getText()).isEqualTo(templates.system());
		assertThat(sent.getUserMessage().getText()).contains("<repo_map>\nthe repo map\n</repo_map>")
				.contains("Repository: acme/shop");
		assertThat(sent.getOptions()).isInstanceOfSatisfying(AnthropicChatOptions.class, options -> {
			assertThat(options.getModel()).isEqualTo("claude-haiku-4-5");
			assertThat(options.getMaxTokens()).isEqualTo(2000);
			assertThat(options.getOutputSchema()).contains("\"oneLiner\"", "\"additionalProperties\":false");
		});
	}

	@Test
	void eachPurposeUsesItsOwnModelAndTokenLimit() {
		claude.reply("""
				{"score": 14, "coveredPoints": ["a"], "missedPoints": [], "incorrectClaims": [],
				 "feedback": "Good.", "followUpNeeded": false, "followUpReason": ""}
				""");
		Map<String, Object> variables = new HashMap<>();
		List.of("difficulty", "question", "rubric", "codeChunks", "answer").forEach(name -> variables.put(name, "x"));

		EvaluationOutput evaluation = gateway.call(
				new LlmRequest<>(LlmPurpose.EVALUATION, variables, EvaluationOutput.class, project, null));

		assertThat(evaluation.score()).isEqualTo(10);   // clamped by normalized()
		assertThat(claude.prompts().getFirst().getOptions()).isInstanceOfSatisfying(AnthropicChatOptions.class,
				options -> {
					assertThat(options.getModel()).isEqualTo("claude-sonnet-5");
					assertThat(options.getMaxTokens()).isEqualTo(1200);
				});
	}

	/** Acceptance criterion: failures are recorded too. A bad reply gets one more try, with the error. */
	@Test
	void anUnreadableReplyIsRetriedOnceWithTheError() {
		claude.reply("Sure! Here is the summary you asked for.").reply(VALID_SUMMARY);

		SummaryOutput summary = gateway.call(summaryRequest(null));

		assertThat(summary.oneLiner()).isEqualTo("A small order service.");
		assertThat(claude.prompts()).hasSize(2);
		assertThat(claude.prompts().get(1).getUserMessage().getText())
				.contains("Your previous output failed validation:");
		assertThat(rows()).extracting(LlmCall::getAttempt, LlmCall::isSuccess, LlmCall::getErrorType)
				.containsExactly(tuple(1, false, "OUTPUT_INVALID"),
						tuple(2, true, null));
		assertThat(rows().getFirst().getInputTokens()).isEqualTo(StubChatModel.INPUT_TOKENS);   // it still cost tokens
	}

	@Test
	void twoUnreadableRepliesGiveAnOutputError() {
		claude.reply("not json").reply("{\"oneLiner\": ");

		assertThatThrownBy(() -> gateway.call(summaryRequest(null))).isInstanceOf(LlmOutputException.class)
				.satisfies(ex -> assertThat(((LlmOutputException) ex).code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
		assertThat(rows()).hasSize(2).allSatisfy(row -> {
			assertThat(row.isSuccess()).isFalse();
			assertThat(row.getErrorType()).isEqualTo("OUTPUT_INVALID");
		});
		assertThat(meters.get("ri.llm.call").tag("outcome", "output_invalid").timer().count()).isEqualTo(2);
	}

	@Test
	void anApiFailureGivesAnUnavailableErrorAndOneRow() {
		claude.fail(new IllegalStateException("connection reset"));

		assertThatThrownBy(() -> gateway.call(summaryRequest(null))).isInstanceOf(LlmUnavailableException.class)
				.hasRootCauseMessage("connection reset");
		assertThat(claude.prompts()).hasSize(1);   // no second attempt: the Anthropic client already retried
		assertThat(rows()).singleElement().satisfies(row -> {
			assertThat(row.isSuccess()).isFalse();
			assertThat(row.getErrorType()).isEqualTo("IllegalStateException");
			assertThat(row.getInputTokens()).isNull();
		});
	}

	@Test
	void withoutBudgetThereIsNoCallAndNoRow() {
		UUID session = newSession(properties.llm().maxCallsPerSession());

		assertThatThrownBy(() -> gateway.call(summaryRequest(session))).isInstanceOf(BudgetExceededException.class);
		assertThat(claude.prompts()).isEmpty();
		assertThat(calls.count()).isZero();
	}

	@Test
	void everyAttemptCountsAgainstTheSessionBudget() {
		UUID session = newSession(0);
		claude.reply("not json").reply(VALID_SUMMARY);

		gateway.call(summaryRequest(session));

		assertThat(jdbc.queryForObject("SELECT llm_call_count FROM interview_session WHERE id = ?", Integer.class,
				session)).isEqualTo(2);
		assertThat(rows()).allSatisfy(row -> assertThat(row.getSessionId()).isEqualTo(session));
	}

	@Test
	void callingInsideADatabaseTransactionIsRejected() {
		claude.reply(VALID_SUMMARY);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> gateway.call(summaryRequest(null))))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("inside a database transaction");
		assertThat(claude.prompts()).isEmpty();
	}

	private LlmRequest<SummaryOutput> summaryRequest(UUID session) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("repoName", "acme/shop");
		variables.put("goal", "Learn Spring");
		variables.put("domain", null);
		variables.put("repoMap", "the repo map");
		variables.put("buildFiles", "<dependencies/>");
		variables.put("readme", "# Shop");
		return new LlmRequest<>(LlmPurpose.SUMMARY, variables, SummaryOutput.class, project, session);
	}

	private UUID newSession(int callsUsed) {
		return jdbc.queryForObject("""
				INSERT INTO interview_session (project_id, status, difficulty, planned_question_count, llm_call_count)
				VALUES (?, 'IN_PROGRESS', 'JUNIOR', 5, ?)
				RETURNING id
				""", UUID.class, project, callsUsed);
	}

	private List<LlmCall> rows() {
		return calls.findAll().stream()
				.sorted(Comparator.comparing(LlmCall::getAttempt))
				.toList();
	}

}
