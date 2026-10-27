package com.aryankanani.repointerviewer.interview;

/**
 * A place in the repository a question is about (LLD §3.4), stored as JSON in {@code question.code_refs}. Only
 * refs that point inside the code sent with the prompt are kept ({@code CodeRefValidator}). Field names match the
 * GraphQL type {@code CodeRef}; its {@code snippet} comes in task 1.13.
 */
public record CodeRef(String path, int startLine, int endLine) {
}
