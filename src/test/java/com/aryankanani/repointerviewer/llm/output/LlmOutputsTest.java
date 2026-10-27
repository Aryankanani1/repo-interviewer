package com.aryankanani.repointerviewer.llm.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

/** Task 1.8: model output is normalized in code, never trusted for shape or numbers (LLD §8.2, D-007). */
class LlmOutputsTest {

	@Test
	void scoreIsClampedToZeroToTen() {
		assertThat(evaluation(14).normalized().score()).isEqualTo(10);
		assertThat(evaluation(-3).normalized().score()).isZero();
		assertThat(evaluation(7).normalized().score()).isEqualTo(7);
	}

	@Test
	void missingListsBecomeEmptyAndTextIsTrimmed() {
		SummaryOutput summary = new SummaryOutput("  An order service.\n", null, null, null,
				List.of("  Outbox in OrderService  ", "   ", ""), null).normalized();

		assertThat(summary.oneLiner()).isEqualTo("An order service.");
		assertThat(summary.architectureStyle()).isEmpty();
		assertThat(summary.techStack()).isEmpty();
		assertThat(summary.modules()).isEmpty();
		assertThat(summary.notablePatterns()).containsExactly("Outbox in OrderService");
		assertThat(summary.riskAreas()).isEmpty();
	}

	@Test
	void listsAreCappedAtWhatThePromptAskedFor() {
		List<String> many = IntStream.range(0, 12).mapToObj(i -> "point " + i).toList();
		List<CodeRefOutput> refs = IntStream.range(0, 5).mapToObj(i -> new CodeRefOutput("A.java", i + 1, i + 2)).toList();

		QuestionOutput question = new QuestionOutput("Why?", refs, new RubricOutput(many, many, many), many)
				.normalized();

		assertThat(question.codeRefs()).hasSize(3);
		assertThat(question.rubric().mustMention()).hasSize(4);
		assertThat(question.rubric().goodToMention()).hasSize(3);
		assertThat(question.rubric().redFlags()).hasSize(3);
		assertThat(question.idealAnswerOutline()).hasSize(5);
	}

	@Test
	void missingNestedRecordsAndNullItemsAreHandled() {
		QuestionOutput question = new QuestionOutput(null, Arrays.asList(null, new CodeRefOutput(" A.java ", 1, 2)),
				null, null).normalized();

		assertThat(question.question()).isEmpty();
		assertThat(question.codeRefs()).containsExactly(new CodeRefOutput("A.java", 1, 2));
		assertThat(question.rubric()).isEqualTo(new RubricOutput(List.of(), List.of(), List.of()));
		assertThat(question.idealAnswerOutline()).isEmpty();
	}

	@Test
	void reportAndPlanAreCapped() {
		List<String> many = IntStream.range(0, 9).mapToObj(i -> "item " + i).toList();
		List<ReviewTopicOutput> topics = IntStream.range(0, 9).mapToObj(i -> new ReviewTopicOutput("t" + i, "w")).toList();
		List<PlannedTopic> planned = IntStream.range(0, 12)
				.mapToObj(i -> new PlannedTopic("t" + i, QuestionCategory.TESTING, many, many, "r")).toList();

		ReportOutput report = new ReportOutput("s", many, many, topics).normalized();
		PlanOutput plan = new PlanOutput(planned).normalized();

		assertThat(report.strengths()).hasSize(4);
		assertThat(report.weaknesses()).hasSize(4);
		assertThat(report.topicsToReview()).hasSize(5);
		assertThat(plan.topics()).hasSize(8);
		assertThat(plan.topics().getFirst().targetPaths()).hasSize(5);
	}

	/** What Anthropic receives as outputSchema: it needs additionalProperties false on every object. */
	@Test
	void schemaForbidsExtraPropertiesAndListsTheAllowedCategories() {
		String schema = new BeanOutputConverter<>(PlanOutput.class).getJsonSchema();

		assertThat(schema).contains("\"additionalProperties\" : false");
		for (QuestionCategory category : QuestionCategory.values()) {
			assertThat(schema).contains("\"" + category.name() + "\"");
		}
	}

	@Test
	void replyJsonIsReadIntoTheRecord() {
		PlanOutput plan = new BeanOutputConverter<>(PlanOutput.class).convert("""
				{"topics": [{"title": "Refunds", "category": "CONCURRENCY",
				  "targetPaths": ["src/main/java/A.java"], "targetSymbols": ["A#refund"], "rationale": "Races."}]}
				""");

		assertThat(plan.topics()).singleElement().satisfies(topic -> {
			assertThat(topic.category()).isEqualTo(QuestionCategory.CONCURRENCY);
			assertThat(topic.targetSymbols()).containsExactly("A#refund");
		});
	}

	private static EvaluationOutput evaluation(int score) {
		return new EvaluationOutput(score, List.of(), List.of(), List.of(), "ok", false, "");
	}

}
