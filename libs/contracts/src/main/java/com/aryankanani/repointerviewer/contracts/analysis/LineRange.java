package com.aryankanani.repointerviewer.contracts.analysis;

/** Lines of one file, 1-based and inclusive: what a question's code ref points at. */
public record LineRange(String path, int startLine, int endLine) {
}
