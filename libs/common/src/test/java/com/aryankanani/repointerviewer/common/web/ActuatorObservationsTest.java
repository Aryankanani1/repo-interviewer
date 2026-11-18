package com.aryankanani.repointerviewer.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Task 4.3: requests to the actuator are not observed; every other request and observation is. */
class ActuatorObservationsTest {

	private final ObservationPredicate predicate = new ActuatorObservations().noObservationForActuatorRequests();

	@Test
	void aScrapeOrAProbeIsNotObserved() {
		assertThat(observed("/actuator/prometheus")).isFalse();
		assertThat(observed("/actuator/health/readiness")).isFalse();
	}

	@Test
	void everythingElseIs() {
		assertThat(observed("/graphql")).isTrue();
		assertThat(observed("/internal/projects/1/context")).isTrue();
		assertThat(predicate.test("ri.llm.call", new Observation.Context())).isTrue();
	}

	private boolean observed(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		return predicate.test("http.server.requests",
				new ServerRequestObservationContext(request, new MockHttpServletResponse()));
	}

}
