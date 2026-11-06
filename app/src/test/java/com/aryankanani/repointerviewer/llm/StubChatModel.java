package com.aryankanani.repointerviewer.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A fake Claude behind the real {@link SpringAiLlmGateway}: returns the queued replies in order (text, or an
 * exception to throw) and remembers every prompt it received. Each reply reports {@value #INPUT_TOKENS} input and
 * {@value #OUTPUT_TOKENS} output tokens.
 *
 * <p>Its default options are {@link AnthropicChatOptions}, like the real Anthropic model's. That matters:
 * ChatClient copies the model's default options and merges the request's options into that copy
 * ({@code combineWith}), so the prompt the tests inspect went through the same merge as in production.
 */
class StubChatModel implements ChatModel {

	static final int INPUT_TOKENS = 120;

	static final int OUTPUT_TOKENS = 40;

	private final Deque<Object> replies = new ArrayDeque<>();

	private final List<Prompt> prompts = new ArrayList<>();

	StubChatModel reply(String text) {
		replies.add(text);
		return this;
	}

	StubChatModel fail(RuntimeException failure) {
		replies.add(failure);
		return this;
	}

	List<Prompt> prompts() {
		return List.copyOf(prompts);
	}

	/** Like application.yml: spring.ai.anthropic.chat.model and max-tokens. */
	@Override
	public ChatOptions getOptions() {
		return AnthropicChatOptions.builder().model("claude-sonnet-5").maxTokens(2048).build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		prompts.add(prompt);
		Object next = replies.poll();
		if (next == null) {
			throw new IllegalStateException("StubChatModel: no reply queued");
		}
		if (next instanceof RuntimeException failure) {
			throw failure;
		}
		return new ChatResponse(List.of(new Generation(new AssistantMessage((String) next))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(INPUT_TOKENS, OUTPUT_TOKENS)).build());
	}

}
