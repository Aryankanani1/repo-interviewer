package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.llm.output.LlmOutput;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One model call (LLD §8.1).
 *
 * @param variables the values for the purpose's template; must match its placeholders exactly. Values may be
 *        null ("(not provided)") or lists ("- item" lines).
 * @param outputType the record the model must return; its JSON schema is sent to the model
 * @param projectId nullable; recorded in {@code llm_call}
 * @param sessionId nullable; when set, the call counts against that interview's budget
 */
public record LlmRequest<T extends LlmOutput<T>>(LlmPurpose purpose, Map<String, Object> variables,
		Class<T> outputType, UUID projectId, UUID sessionId) {

	public LlmRequest {
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(outputType, "outputType");
		// A copy that allows null values, which Map.copyOf does not.
		variables = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(variables, "variables")));
	}

	/** Leaves out the variables: they hold repository text and candidate answers. */
	@Override
	public String toString() {
		return "LlmRequest[purpose=" + purpose + ", outputType=" + outputType.getSimpleName() + ", projectId="
				+ projectId + ", sessionId=" + sessionId + "]";
	}

}
