package com.aryankanani.repointerviewer.llm.output;

/**
 * A record the model returns (structured output, LLD §8.2). The gateway sends its JSON schema to the model and
 * reads the reply into it.
 *
 * <p>Model output is never trusted for shape or numbers (DECISIONS.md D-007): after every call the gateway
 * calls {@link #normalized()}, which trims text, turns missing lists into empty ones, caps list sizes at what
 * the prompt asked for, and clamps numbers. Checks that need the database (do these paths exist?) come later,
 * in the code that uses the output.
 */
public interface LlmOutput<T extends LlmOutput<T>> {

	T normalized();

}
