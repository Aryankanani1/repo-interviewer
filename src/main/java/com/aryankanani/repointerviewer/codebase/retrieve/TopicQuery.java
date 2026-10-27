package com.aryankanani.repointerviewer.codebase.retrieve;

import java.util.List;

/**
 * What to retrieve code for (LLD §7).
 *
 * @param text searched by meaning, for example the topic title and rationale
 * @param targetPaths files the plan named; always included, whatever their role
 * @param targetSymbols classes or methods the plan named
 * @param includeTests whether search by meaning may return chunks of TEST files (only for TESTING topics)
 */
public record TopicQuery(String text, List<String> targetPaths, List<String> targetSymbols, boolean includeTests) {

	public TopicQuery {
		targetPaths = List.copyOf(targetPaths);
		targetSymbols = List.copyOf(targetSymbols);
	}

}
