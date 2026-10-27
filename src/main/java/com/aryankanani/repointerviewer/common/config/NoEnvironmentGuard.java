package com.aryankanani.repointerviewer.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stops startup when no environment was chosen (DECISIONS.md D-015). Spring activates the built-in profile
 * {@code default} exactly when no profile is set, so this bean only exists then.
 *
 * <p>A {@link BeanFactoryPostProcessor} runs before Spring creates any normal bean, so this message comes
 * first. Without it the app still fails, but later and with a confusing error from the connection pool
 * ({@code 'url' must start with "jdbc"}), because application.yml deliberately has no database fallback.
 */
@Component
@Profile("default")
class NoEnvironmentGuard implements BeanFactoryPostProcessor {

	static final String MESSAGE = "No environment selected. Set SPRING_PROFILES_ACTIVE to dev, stage or prod "
			+ "(./mvnw spring-boot:run uses dev; the tests use test).";

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		throw new IllegalStateException(MESSAGE);
	}

}
