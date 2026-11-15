package com.aryankanani.repointerviewer.interview;

import java.util.List;

/**
 * How an answer is graded (LLD §3.4), stored as JSON in {@code question.rubric}. Hidden from the candidate: the
 * GraphQL {@code Question} type has no rubric field.
 */
public record Rubric(List<String> mustMention, List<String> goodToMention, List<String> redFlags) {
}
