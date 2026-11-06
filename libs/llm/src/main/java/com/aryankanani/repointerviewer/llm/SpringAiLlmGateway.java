package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.llm.LlmCall.Tokens;
import com.aryankanani.repointerviewer.llm.output.LlmOutput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link LlmGateway} on Spring AI's {@link ChatClient} and Anthropic (LLD §8.1). For each attempt:
 * <ol>
 * <li>{@link LlmBudget#reserve}: no budget, no call, no ledger row.</li>
 * <li>Send the system prompt, the filled-in purpose template, the purpose's model and output-token limit, and
 * the output record's JSON schema as Anthropic's {@code outputSchema}. With the schema, Anthropic itself makes the
 * reply match it ("native structured output").</li>
 * <li>Read the reply into the record ourselves ({@link BeanOutputConverter}), then normalize it.</li>
 * <li>Write one {@code llm_call} row and record the {@code ri.llm.call} timer, success or failure.</li>
 * </ol>
 * Two kinds of failure, kept apart on purpose:
 * <ul>
 * <li>The API failed (network, 5xx, rate limit) after the Anthropic client's own retries
 * ({@code spring.ai.anthropic.max-retries}): {@link LlmUnavailableException}. Worth retrying later.</li>
 * <li>The reply could not be read into the record: one more attempt with the error added to the prompt, then
 * {@link LlmOutputException}. Each attempt goes through the budget and gets its own ledger row.</li>
 * </ul>
 */
@Component
class SpringAiLlmGateway implements LlmGateway {

	static final int MAX_ATTEMPTS = 2;

	static final String OUTPUT_INVALID = "OUTPUT_INVALID";

	private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

	private final ChatClient chatClient;

	private final PromptTemplates templates;

	private final LlmBudget budget;

	private final LlmCallLedger ledger;

	private final AppProperties.Llm settings;

	private final MeterRegistry meters;

	SpringAiLlmGateway(ChatClient.Builder chatClientBuilder, PromptTemplates templates, LlmBudget budget,
			LlmCallLedger ledger, AppProperties properties, MeterRegistry meters) {
		this.chatClient = chatClientBuilder.build();
		this.templates = templates;
		this.budget = budget;
		this.ledger = ledger;
		this.settings = properties.llm();
		this.meters = meters;
	}

	@Override
	public <T extends LlmOutput<T>> T call(LlmRequest<T> request) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// A transaction open across a 5-60 s network call holds its locks and connection the whole time.
			throw new IllegalStateException("Never call the LLM inside a database transaction (CLAUDE.md)");
		}
		LlmPurpose purpose = request.purpose();
		String model = model(purpose);
		String version = templates.version(purpose.template());
		String prompt = templates.render(purpose.template(), request.variables());
		BeanOutputConverter<T> converter = new BeanOutputConverter<>(request.outputType());

		String correction = "";
		for (int attempt = 1;; attempt++) {
			budget.reserve(request.sessionId());
			long start = System.nanoTime();
			ChatResponse response;
			try {
				response = chatClient.prompt()
						.system(templates.system())
						.user(prompt + correction)
						.options(AnthropicChatOptions.builder()
								.model(model)
								.maxTokens(maxOutputTokens(purpose))
								.timeout(settings.timeout())
								.outputSchema(converter.getJsonSchema()))
						.call()
						.chatResponse();
			}
			catch (RuntimeException ex) {
				finish(request, model, version, attempt, start, Tokens.NONE, ex.getClass().getSimpleName());
				throw new LlmUnavailableException(ex);
			}
			Tokens tokens = tokens(response);
			try {
				T output = converter.convert(response.getResult().getOutput().getText()).normalized();
				finish(request, model, version, attempt, start, tokens, null);
				return output;
			}
			catch (RuntimeException ex) {
				finish(request, model, version, attempt, start, tokens, OUTPUT_INVALID);
				if (attempt == MAX_ATTEMPTS) {
					throw new LlmOutputException(ex);
				}
				// The model sees what was wrong, so the second attempt can fix it instead of repeating it.
				correction = "\n\nYour previous output failed validation: " + firstLine(ex)
						+ "\nReply again with output that matches the schema exactly.";
			}
		}
	}

	@Override
	public String promptVersion(LlmPurpose purpose) {
		return templates.version(purpose.template());
	}

	private void finish(LlmRequest<?> request, String model, String version, int attempt, long start, Tokens tokens,
			String errorType) {
		long nanos = System.nanoTime() - start;
		String outcome = errorType == null ? "success" : (errorType.equals(OUTPUT_INVALID) ? "output_invalid" : "unavailable");
		Timer.builder("ri.llm.call")
				.tag("purpose", request.purpose().name())
				.tag("model", model)
				.tag("outcome", outcome)
				.register(meters)
				.record(Duration.ofNanos(nanos));
		int latencyMs = (int) Duration.ofNanos(nanos).toMillis();
		ledger.record(new LlmCall(request, model, version, attempt, latencyMs, tokens, errorType));
		// Never the prompt or the reply: they hold repository code and candidate answers.
		log.info("LLM call {} model={} attempt={} outcome={} latencyMs={} inputTokens={} outputTokens={}",
				request.purpose(), model, attempt, outcome, latencyMs, tokens.input(), tokens.output());
	}

	private static Tokens tokens(ChatResponse response) {
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		if (usage == null) {
			return Tokens.NONE;
		}
		Long cached = usage.getCacheReadInputTokens();
		return new Tokens(usage.getPromptTokens(), usage.getCompletionTokens(),
				cached == null ? null : Math.toIntExact(cached));
	}

	/** Parser messages can quote the whole reply; the first line says what was wrong. */
	private static String firstLine(Exception ex) {
		String message = String.valueOf(ex.getMessage());
		String line = message.lines().findFirst().orElse(message);
		return line.length() <= 300 ? line : line.substring(0, 300);
	}

	private String model(LlmPurpose purpose) {
		AppProperties.Models models = settings.models();
		return switch (purpose) {
			case SUMMARY -> models.summary();
			case PLAN -> models.plan();
			case QUESTION -> models.question();
			case FOLLOW_UP -> models.followUp();
			case EVALUATION -> models.evaluation();
			case REPORT -> models.report();
		};
	}

	private int maxOutputTokens(LlmPurpose purpose) {
		AppProperties.MaxOutputTokens limits = settings.maxOutputTokens();
		return switch (purpose) {
			case SUMMARY -> limits.summary();
			case PLAN -> limits.plan();
			case QUESTION -> limits.question();
			case FOLLOW_UP -> limits.followUp();
			case EVALUATION -> limits.evaluation();
			case REPORT -> limits.report();
		};
	}

}
