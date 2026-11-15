/**
 * The interview side's internal API: {@code InterviewApi} and the records it sends, the enums and JSON records they
 * share with the GraphQL schema, and {@code AnswerFlowEvent}, the payload of the answer flow's Kafka events (task 4.1,
 * DECISIONS.md D-026).
 *
 * <p>May depend on: nothing in this application. Used by {@code interview} (which implements the API and writes the
 * events), by {@code api} (which calls the API and reads the events for subscriptions) and by {@code llm} (the plan
 * output uses {@code QuestionCategory}). Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.contracts.interview;
