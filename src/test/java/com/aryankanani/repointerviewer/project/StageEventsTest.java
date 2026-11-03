package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.messaging.InvalidEventException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Which messages a stage accepts (task 2.4): anything else is an {@link InvalidEventException}, for the DLT. */
class StageEventsTest {

	private static final UUID EVENT_ID = UUID.randomUUID();

	private static final UUID PROJECT_ID = UUID.randomUUID();

	private final StageEvents events = new StageEvents(JsonMapper.builder().build());

	@Test
	void acceptsTheExpectedTypeAtVersionOne() {
		assertThat(events.read(event("Embedded", 1), "Embedded").aggregateId()).isEqualTo(PROJECT_ID);
	}

	@Test
	void rejectsAnotherTypeAnUnknownVersionAndWhatIsNotAnEnvelope() {
		assertThatThrownBy(() -> events.read(event("ChunksReady", 1), "Embedded"))
				.isInstanceOf(InvalidEventException.class).hasMessageContaining("expected Embedded version 1");
		assertThatThrownBy(() -> events.read(event("Embedded", 2), "Embedded"))
				.isInstanceOf(InvalidEventException.class);
		assertThatThrownBy(() -> events.read("{\"type\": \"Embedded\", \"version\": 1}", "Embedded"))
				.isInstanceOf(InvalidEventException.class).hasMessageContaining("without eventId");
		assertThatThrownBy(() -> events.read("not json", "Embedded")).isInstanceOf(InvalidEventException.class);
		assertThatThrownBy(() -> events.read(null, "Embedded")).isInstanceOf(InvalidEventException.class);
	}

	private static String event(String type, int version) {
		return """
				{"eventId": "%s", "type": "%s", "version": %d, "occurredAt": "2026-11-02T12:00:00Z",
				 "aggregateId": "%s", "payload": {"projectId": "%s"}}""".formatted(EVENT_ID, type, version, PROJECT_ID,
				PROJECT_ID);
	}

}
