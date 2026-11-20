package com.aryankanani.repointerviewer.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call the gateway (task 4.4, DECISIONS.md D-030): every request needs a JWT signed with RS256 by the key
 * whose public half is {@code spring.security.oauth2.resourceserver.jwt.public-key-location}, sent as
 * {@code Authorization: Bearer <token>}. Without one, or with a bad signature or an expired one, the answer is
 * HTTP 401 before GraphQL runs. The user is the token's {@code sub} claim ({@link CurrentUser}); what each user may
 * reach is checked per operation ({@link Ownership}).
 *
 * <p>Open without a token: health (Kubernetes probes), Prometheus metrics (scraped inside the network), the
 * GraphiQL page, whose requests carry a token in its headers editor, and the web page with the documents it loads
 * (task 5.2), whose requests carry the token pasted into it. Stateless: no session and no cookie, so no CSRF
 * protection is needed either, since a browser never adds a bearer token to a request on its own.
 *
 * <p>Only in a web application: a context without a web server (a test's second gateway instance, which only
 * consumes Kafka) has no requests to filter.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/actuator/health/**", "/actuator/prometheus", "/graphiql", "/error", "/",
								"/index.html", "/documents/**")
						.permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
				.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable)
				.build();
	}

}
