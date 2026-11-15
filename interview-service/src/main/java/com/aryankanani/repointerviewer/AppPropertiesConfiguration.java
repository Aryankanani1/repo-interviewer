package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.config.ConsumerRetryProperties;
import com.aryankanani.repointerviewer.common.config.RolesProperties;
import com.aryankanani.repointerviewer.interview.InterviewProperties;
import com.aryankanani.repointerviewer.interview.RetrievalProperties;
import com.aryankanani.repointerviewer.llm.LlmProperties;
import com.aryankanani.repointerviewer.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code app.*} settings of interview-service (task 4.1 step 4a): exactly these records are bound and validated at
 * startup, so its application.yml carries only these sections. A misspelled key inside one of them stops the start
 * ({@code ignoreUnknownFields = false}); a whole section no record binds is not read at all. Until then one
 * {@code AppProperties} record held every app's sections. The tests bind the same list ({@code AppPropertiesTest},
 * {@code EnvironmentProfilesTest}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ InterviewProperties.class, RetrievalProperties.class, LlmProperties.class,
		OutboxProperties.class, ConsumerRetryProperties.class, RolesProperties.class })
public class AppPropertiesConfiguration {
}
