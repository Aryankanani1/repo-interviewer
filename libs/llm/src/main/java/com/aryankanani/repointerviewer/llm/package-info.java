/**
 * The only way to call a chat model: {@code LlmGateway}, prompt templates, model routing per
 * purpose, structured output, retries, budgets and the {@code llm_call} ledger.
 *
 * <p>May depend on: {@code common}. The only module allowed to use {@code org.springframework.ai.chat}.
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.llm;
