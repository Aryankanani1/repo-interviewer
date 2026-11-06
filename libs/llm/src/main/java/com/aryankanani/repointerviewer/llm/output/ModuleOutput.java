package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/** One module of a {@link SummaryOutput}. */
public record ModuleOutput(String name, String responsibility, List<String> keyClasses) {

	static final int MAX_KEY_CLASSES = 4;

	ModuleOutput normalized() {
		return new ModuleOutput(Normalize.text(name), Normalize.text(responsibility),
				Normalize.texts(keyClasses, MAX_KEY_CLASSES));
	}

}
