package com.aryankanani.repointerviewer.interview;

import java.util.UUID;

/** Input for {@link InterviewService#endInterview}. */
public record EndInterviewCommand(UUID sessionId) {
}
