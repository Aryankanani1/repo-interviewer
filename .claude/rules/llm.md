---
paths:
  - "src/main/java/**/llm/**"
  - "src/main/java/**/interview/**"
  - "src/main/resources/prompts/**"
---

# LLM rules

- All chat calls go through `LlmGateway.call(LlmRequest)`. It checks the budget, renders templates, routes the model by purpose, parses structured output, retries, and writes exactly one `llm_call` row per attempt, success or failure.
- Prompt files: `{name}` placeholders only. No other `{` or `}` characters (StringTemplate delimiters). The output shape comes from the Java record passed as `outputType`, not from JSON pasted in the prompt.
- Untrusted text (repo content, candidate answers, the candidate's goal field) always sits inside its tag: `<repo_map>`, `<repo_content>`, `<code_chunk>`, `<candidate_answer>`.
- After every call, normalize the output in code: null lists to empty, trim, clamp scores, cap list sizes. Then validate (refs, paths, symbols). Never trust model output for numbers or thresholds.
- Changing a prompt changes `prompt_version` automatically (content hash). Note prompt changes and the reason in the ROADMAP Status log, and re-run the eval harness when it exists.
- Never put an LLM call inside a `@Transactional` method.
- Tests use `FakeLlmGateway`. Real calls only in `@Tag("eval")` tests, and only after Aryan says yes.
