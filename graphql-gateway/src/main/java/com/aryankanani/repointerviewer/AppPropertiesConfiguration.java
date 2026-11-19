package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.api.hardening.GraphQlHardeningProperties;
import com.aryankanani.repointerviewer.api.ratelimit.RateLimitProperties;
import com.aryankanani.repointerviewer.common.config.ConsumerRetryProperties;
import com.aryankanani.repointerviewer.common.config.ServiceCallProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code app.*} settings of graphql-gateway (task 4.1 step 4a): exactly these records are bound and validated at
 * startup, so its application.yml carries only these sections. A misspelled key inside one of them stops the start
 * ({@code ignoreUnknownFields = false}); a whole section no record binds is not read at all. Until then one
 * {@code AppProperties} record held every app's sections. The tests bind the same list ({@code AppPropertiesTest},
 * {@code EnvironmentProfilesTest}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ ConsumerRetryProperties.class, ServiceCallProperties.class,
		RateLimitProperties.class, GraphQlHardeningProperties.class })
public class AppPropertiesConfiguration {
}
