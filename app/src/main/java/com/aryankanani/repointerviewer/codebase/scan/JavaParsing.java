package com.aryankanani.repointerviewer.codebase.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import java.util.Optional;

/**
 * The one place that decides how Java source is parsed, shared by {@link FileClassifier} and the chunkers so
 * they never disagree about which files parse. Syntax only: nothing is compiled or resolved.
 */
public final class JavaParsing {

	/** Latest LTS. Syntax newer than this fails to parse, and callers fall back to plain text. */
	private static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_25;

	private JavaParsing() {
	}

	/**
	 * A new parser per call: JavaParser instances are not thread-safe, and the callers are shared beans.
	 *
	 * @return the syntax tree, or empty when the source has syntax errors
	 */
	public static Optional<CompilationUnit> parse(String source) {
		ParseResult<CompilationUnit> result = new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL))
				.parse(source);
		return result.isSuccessful() ? result.getResult() : Optional.empty();
	}

}
