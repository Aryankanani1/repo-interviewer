package com.aryankanani.repointerviewer.project;

import java.util.UUID;

/** Published inside the transaction that inserted the project. In Phase 2 it becomes an outbox row. */
record ProjectSubmittedEvent(UUID projectId) {
}
