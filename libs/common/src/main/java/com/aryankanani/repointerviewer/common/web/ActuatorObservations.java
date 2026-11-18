package com.aryankanani.repointerviewer.common.web;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * No observation for a request to {@code /actuator/**} (task 4.3), in every app. Prometheus scrapes each app every
 * 15 s and Kubernetes probes it every few seconds: each would be a trace in Jaeger and a series in
 * {@code http_server_requests}, burying the requests people make. Only the servlet request's path is read.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorObservations {

	@Bean
	ObservationPredicate noObservationForActuatorRequests() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext request
				&& request.getCarrier().getRequestURI().startsWith("/actuator"));
	}

}
