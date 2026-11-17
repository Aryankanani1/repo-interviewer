package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.config.ServiceCallProperties;
import com.aryankanani.repointerviewer.common.web.DomainErrorHandler;
import com.aryankanani.repointerviewer.common.web.ServiceCallGuard;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link AnalysisApi} bean of interview-service: an HTTP client for analysis-service (task 4.1 step 3b; the app
 * has the same one). Spring builds it from the interface's {@code @HttpExchange} annotations, the same ones that map
 * analysis-service's controller.
 * Base URL and timeouts: {@code spring.http.serviceclient.analysis.*}. An error that analysis-service answered with
 * a problem detail throws the same {@code DomainException} as before the split ({@link DomainErrorHandler}). With
 * analysis-service down, a call fails fast with SERVICE_UNAVAILABLE: a retry and a circuit breaker
 * ({@link ServiceCallGuard}, task 4.2, {@code app.service-calls}).
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = AnalysisClientConfiguration.GROUP, types = AnalysisApi.class)
class AnalysisClientConfiguration {

	static final String GROUP = "analysis";

	@Bean
	RestClientHttpServiceGroupConfigurer analysisCalls(JsonMapper jsonMapper, ServiceCallProperties calls) {
		DomainErrorHandler errors = new DomainErrorHandler(jsonMapper);
		return groups -> groups.filterByName(GROUP).forEachClient((group, builder) -> builder
				.requestInterceptor(new ServiceCallGuard("analysis service", calls))
				.defaultStatusHandler(HttpStatusCode::isError, errors));
	}

}
