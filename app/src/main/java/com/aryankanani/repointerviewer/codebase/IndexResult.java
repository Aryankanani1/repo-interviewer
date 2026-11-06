package com.aryankanani.repointerviewer.codebase;

/**
 * What {@link CodebaseIndexer#index} found and saved: the numbers for {@code project}'s stats, the repo map, and
 * the extra text the summary prompt needs.
 *
 * @param primaryLanguage the programming language with the most lines, for example {@code java}
 * @param repoMap the token-budgeted outline (LLD §6.9)
 * @param buildFiles the dependencies of {@code pom.xml} / Gradle build files as {@code - group:artifact} lines;
 *        null when there are none
 * @param readme the first {@value SummarySources#README_LINES} lines of the README; null when there is none
 */
public record IndexResult(int fileCount, int javaFileCount, int totalLines, int chunkCount, String primaryLanguage,
		String repoMap, String buildFiles, String readme) {

	/** Leaves out the texts: they are repository content. */
	@Override
	public String toString() {
		return "IndexResult[fileCount=" + fileCount + ", javaFileCount=" + javaFileCount + ", totalLines=" + totalLines
				+ ", chunkCount=" + chunkCount + ", primaryLanguage=" + primaryLanguage + "]";
	}

}
