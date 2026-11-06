package com.aryankanani.repointerviewer.project;

import java.util.UUID;

/**
 * A status change that is not an arrow in the state machine (LLD §3.2). Always a bug in our code, never a user
 * mistake, so it is not a {@code DomainException}.
 */
public class IllegalStateTransitionException extends IllegalStateException {

	IllegalStateTransitionException(UUID projectId, AnalysisStatus from, AnalysisStatus to) {
		super("Project " + projectId + " cannot go from " + from + " to " + to);
	}

}
