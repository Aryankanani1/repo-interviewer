package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.web.DomainErrorHandler;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link AnalysisApi} bean of this app: an HTTP client for analysis-service (task 4.1 step 2b). Spring builds it
 * from the interface's {@code @HttpExchange} annotations, the same ones that map analysis-service's controller.
 * Base URL and timeouts: {@code spring.http.serviceclient.analysis.*}. An error that analysis-service answered with
 * a problem detail throws the same {@code DomainException} as before the split ({@link DomainErrorHandler}).
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = AnalysisClientConfiguration.GROUP, types = AnalysisApi.class)
class AnalysisClientConfiguration {

	static final String GROUP = "analysis";

	@Bean
	RestClientHttpServiceGroupConfigurer analysisErrors(JsonMapper jsonMapper) {
		DomainErrorHandler errors = new DomainErrorHandler(jsonMapper);
		return groups -> groups.filterByName(GROUP)
				.forEachClient((group, builder) -> builder.defaultStatusHandler(HttpStatusCode::isError, errors));
	}

}
