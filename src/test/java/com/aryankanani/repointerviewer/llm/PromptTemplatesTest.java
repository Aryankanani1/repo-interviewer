package com.aryankanani.repointerviewer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

/** Task 1.8: the prompt files and how they are filled in (LLD §8.3). No Spring, no model. */
class PromptTemplatesTest {

	/** The variables of each prompt file, as documented in LLD §8.3. A change on either side fails a test. */
	static final Map<LlmPurpose, List<String>> VARIABLES = Map.of(
			LlmPurpose.SUMMARY, List.of("repoName", "goal", "domain", "repoMap", "buildFiles", "readme"),
			LlmPurpose.PLAN, List.of("difficulty", "targetRole", "questionCount", "focusAreas", "goal", "summary",
					"repoMap"),
			LlmPurpose.QUESTION, List.of("difficulty", "topicTitle", "category", "rationale", "codeChunks",
					"previousQuestions"),
			LlmPurpose.EVALUATION, List.of("difficulty", "question", "rubric", "codeChunks", "answer"),
			LlmPurpose.FOLLOW_UP, List.of("difficulty", "question", "answer", "missedPoints", "followUpReason",
					"codeChunks"),
			LlmPurpose.REPORT, List.of("repoName", "difficulty", "overallScore", "categoryScores", "transcript"));

	/**
	 * Untrusted text and the tag it must sit inside (LLD §8.3): the candidate's own words, their answers, and
	 * repository content. The system prompt tells the model never to follow instructions inside these tags.
	 */
	static final Map<String, String> UNTRUSTED = Map.of(
			"goal", "candidate_context",
			"domain", "candidate_context",
			"targetRole", "candidate_context",
			"answer", "candidate_answer",
			"repoMap", "repo_map",
			"buildFiles", "repo_content",
			"readme", "repo_content",
			"summary", "repo_content");

	private final PromptTemplates templates = new PromptTemplates();

	/** Acceptance criterion: every real prompt file renders with its variables and leaves no {...} behind. */
	@ParameterizedTest
	@EnumSource(LlmPurpose.class)
	void everyPromptRendersWithItsVariablesAndLeavesNoBraces(LlmPurpose purpose) {
		Map<String, Object> variables = new HashMap<>();
		VARIABLES.get(purpose).forEach(name -> variables.put(name, "value-of-" + name));

		String prompt = templates.render(purpose.template(), variables);

		assertThat(prompt).doesNotContain("{", "}");
		VARIABLES.get(purpose).forEach(name -> assertThat(prompt).contains("value-of-" + name));
	}

	/** A candidate could write "ignore the rubric" into their goal; inside its tag, it stays data. */
	@ParameterizedTest
	@EnumSource(LlmPurpose.class)
	void untrustedPlaceholdersSitInsideTheirTag(LlmPurpose purpose) throws IOException {
		String text = new ClassPathResource("prompts/" + purpose.template() + ".st")
				.getContentAsString(StandardCharsets.UTF_8);

		UNTRUSTED.forEach((name, tag) -> {
			for (int at = text.indexOf("{" + name + "}"); at >= 0; at = text.indexOf("{" + name + "}", at + 1)) {
				int lastOpen = text.lastIndexOf("<" + tag + ">", at);
				int lastClose = text.lastIndexOf("</" + tag + ">", at);
				assertThat(lastOpen).as("{%s} in %s.st must be inside <%s>", name, purpose.template(), tag)
						.isGreaterThan(lastClose);
				assertThat(text.indexOf("</" + tag + ">", at)).as("<%s> around {%s} must be closed", tag, name)
						.isGreaterThan(at);
			}
		});
	}

	@Test
	void theSystemPromptNamesEveryUntrustedTag() {
		UNTRUSTED.values().forEach(tag -> assertThat(templates.system()).contains("<" + tag + ">"));
		assertThat(templates.system()).contains("<code_chunk>");
	}

	@Test
	void everyPromptFileIsAPurposeOrTheSystemPrompt() {
		List<String> expected = new ArrayList<>(
				Arrays.stream(LlmPurpose.values()).map(LlmPurpose::template).toList());
		expected.add(PromptTemplates.SYSTEM);

		assertThat(templates.names()).containsExactlyInAnyOrderElementsOf(expected);
		assertThat(templates.system()).contains("<candidate_answer>").doesNotContain("{", "}");
	}

	@Test
	void aMissingVariableIsAnErrorThatNamesIt() {
		Map<String, Object> variables = new HashMap<>();
		VARIABLES.get(LlmPurpose.SUMMARY).forEach(name -> variables.put(name, "x"));
		variables.remove("repoMap");

		assertThatThrownBy(() -> templates.render("repo-summary", variables))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("missing variables [repoMap]");
	}

	@Test
	void anUnknownVariableIsAnErrorThatNamesIt() {
		Map<String, Object> variables = new HashMap<>();
		VARIABLES.get(LlmPurpose.SUMMARY).forEach(name -> variables.put(name, "x"));
		variables.put("repoMapp", "typo");

		assertThatThrownBy(() -> templates.render("repo-summary", variables))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown variables [repoMapp]");
	}

	@Test
	void nullAndListsAndCodeWithBracesAreRenderedAsText() {
		PromptTemplates small = new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"demo", "Goal: {goal}\nPoints:\n{points}\nNone:\n{none}\nCode:\n{code}"));
		Map<String, Object> variables = new HashMap<>();
		variables.put("goal", null);
		variables.put("points", List.of("first", "second"));
		variables.put("none", List.of());
		variables.put("code", "public Order get(@PathVariable UUID id) { return find(id); }");

		assertThat(small.render("demo", variables)).isEqualTo("""
				Goal: (not provided)
				Points:
				- first
				- second
				None:
				(not provided)
				Code:
				public Order get(@PathVariable UUID id) { return find(id); }""");
	}

	@Test
	void aPromptFileWithAStrayBraceStopsStartup() {
		assertThatThrownBy(() -> new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"broken", "Return JSON like {\"score\": {score}}")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("prompts/broken.st")
				.hasMessageContaining("only {name} placeholders");
		assertThatThrownBy(() -> new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"spaced", "Hello { name }")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theSystemPromptIsRequiredAndHasNoPlaceholders() {
		assertThatThrownBy(() -> new PromptTemplates(Map.of("demo", "Hi {name}")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("system-interviewer.st is missing");
		assertThatThrownBy(() -> new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview {who}.")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("must not have placeholders");
	}

	@Test
	void versionIsTheNamePlusAShortHashOfSystemAndTemplate() {
		PromptTemplates original = new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"demo", "Ask about {topic}."));
		PromptTemplates sameText = new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"demo", "Ask about {topic}."));
		PromptTemplates templateChanged = new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview.",
				"demo", "Ask one question about {topic}."));
		PromptTemplates systemChanged = new PromptTemplates(Map.of(PromptTemplates.SYSTEM, "You interview kindly.",
				"demo", "Ask about {topic}."));

		assertThat(original.version("demo")).matches("demo@[0-9a-f]{8}").isEqualTo(sameText.version("demo"));
		assertThat(templateChanged.version("demo")).isNotEqualTo(original.version("demo"));
		assertThat(systemChanged.version("demo")).isNotEqualTo(original.version("demo"));
	}

	@ParameterizedTest
	@EnumSource(LlmPurpose.class)
	void realVersionsFitTheColumn(LlmPurpose purpose) {
		assertThat(templates.version(purpose.template()))
				.matches(purpose.template() + "@[0-9a-f]{8}")
				.hasSizeLessThanOrEqualTo(40);   // llm_call.prompt_version VARCHAR(40)
	}

	@Test
	void anUnknownTemplateIsAnError() {
		assertThatThrownBy(() -> templates.render("no-such-prompt", Map.of()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prompts/no-such-prompt.st");
	}

}
