package com.aryankanani.repointerviewer.common;

/**
 * Rough token count for budgeting (LLD §6.6): about 4 characters per token for English and code. Good enough
 * to size chunks and prompts; the real count comes back from the model in the usage metadata.
 */
public final class TokenEstimator {

	private TokenEstimator() {
	}

	/** {@code ceil(chars / 4)}; 0 for an empty text. */
	public static int estimate(String text) {
		return (text.length() + 3) / 4;
	}

}
