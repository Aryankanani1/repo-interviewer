package com.aryankanani.repointerviewer.llm.output;

/** A code reference the model cites. Line numbers are kept as given; {@code CodeRefValidator} checks them. */
public record CodeRefOutput(String path, int startLine, int endLine) {

	CodeRefOutput normalized() {
		return new CodeRefOutput(Normalize.text(path), startLine, endLine);
	}

}
