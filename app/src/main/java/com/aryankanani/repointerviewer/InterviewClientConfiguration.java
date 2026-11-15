package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.web.DomainErrorHandler;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link InterviewApi} bean of this app: an HTTP client for interview-service (task 4.1 step 3b), built like
 * {@link AnalysisClientConfiguration}'s. Base URL and timeouts: {@code spring.http.serviceclient.interview.*}. An
 * error that interview-service answered with a problem detail throws the same {@code DomainException} as before the
 * split ({@link DomainErrorHandler}).
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = InterviewClientConfiguration.GROUP, types = InterviewApi.class)
class InterviewClientConfiguration {

	static final String GROUP = "interview";

	@Bean
	RestClientHttpServiceGroupConfigurer interviewErrors(JsonMapper jsonMapper) {
		DomainErrorHandler errors = new DomainErrorHandler(jsonMapper);
		return groups -> groups.filterByName(GROUP)
				.forEachClient((group, builder) -> builder.defaultStatusHandler(HttpStatusCode::isError, errors));
	}

}
