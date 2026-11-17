package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.config.ServiceCallProperties;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.function.ThrowingConsumer;

/**
 * Task 4.2 acceptance criterion: with analysis-service stopped, interview-service fails fast with a clear error
 * instead of hanging. Its real {@link AnalysisApi} client, configured from its own application.yml (the JDK
 * HttpClient, the timeouts, {@code app.service-calls}), against real sockets: a port where nothing listens (the
 * service stopped), and one that accepts connections but never answers (the service hung).
 *
 * <p>Every call of interview-service to analysis-service goes through this client, and an interview starts with
 * {@code context()} before saving anything, so a start fails the same way; the system test checks that through the
 * gateway.
 */
class AnalysisServiceDownTest {

	private static final String UNAVAILABLE = "The analysis service is not available right now. Try again in a minute.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // this service's application.yml
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
					ImperativeHttpClientAutoConfiguration.class, HttpServiceClientPropertiesAutoConfiguration.class,
					RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
			.withUserConfiguration(AnalysisClientConfiguration.class, Settings.class);

	@Test
	void withAnalysisServiceStoppedACallFailsFastWithAClearError() throws IOException {
		run(stoppedService(), analysis -> {
			Instant start = Instant.now();

			assertThatThrownBy(() -> analysis.context(UUID.randomUUID()))
					.isInstanceOfSatisfying(ServiceUnavailableException.class, ex -> {
						assertThat(ex.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
						assertThat(ex.getMessage()).isEqualTo(UNAVAILABLE);
					});

			// 3 refused tries, 200 ms and 400 ms apart (app.service-calls)
			assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
		});
	}

	/** The 5th refused try opens the breaker: from then on a call fails without trying the network. */
	@Test
	void onceTheBreakerIsOpenACallFailsAtOnce() throws IOException {
		run(stoppedService(), analysis -> {
			for (int call = 0; call < 2; call++) {
				assertThatThrownBy(() -> analysis.context(UUID.randomUUID()))
						.isInstanceOf(ServiceUnavailableException.class);
			}
			Instant start = Instant.now();

			assertThatThrownBy(() -> analysis.codeNames(UUID.randomUUID()))
					.isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE)
					.hasCauseInstanceOf(CallNotPermittedException.class);

			assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(100));
		});
	}

	/**
	 * analysis-service hung: the connection opens, no answer comes. One try (a request that reached the service is not
	 * sent again), failed at the read timeout, 1 s here instead of 10 s.
	 */
	@Test
	void aHungAnalysisServiceFailsTheCallAtTheReadTimeout() throws IOException {
		try (ServerSocket hung = new ServerSocket(0)) {
			List<Socket> accepted = new CopyOnWriteArrayList<>();
			Thread.ofVirtual().start(() -> {
				try {
					while (true) {
						accepted.add(hung.accept());   // kept open, never read, never answered
					}
				}
				catch (IOException closed) {
					// the test is over
				}
			});
			contextRunner.withPropertyValues("spring.http.serviceclient.analysis.base-url=http://localhost:"
					+ hung.getLocalPort(), "spring.http.serviceclient.analysis.read-timeout=1s").run(context -> {
						AnalysisApi analysis = context.getBean(AnalysisApi.class);
						Instant start = Instant.now();

						assertThatThrownBy(() -> analysis.context(UUID.randomUUID()))
								.isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE);

						assertThat(Duration.between(start, Instant.now())).isBetween(Duration.ofSeconds(1),
								Duration.ofSeconds(3));
						assertThat(accepted).hasSize(1);
					});
			for (Socket socket : accepted) {
				socket.close();
			}
		}
	}

	private void run(String baseUrl, ThrowingConsumer<AnalysisApi> test) {
		contextRunner.withPropertyValues("spring.http.serviceclient.analysis.base-url=" + baseUrl)
				.run(context -> test.accept(context.getBean(AnalysisApi.class)));
	}

	/** A port that was free a moment ago and is closed again: a connection to it is refused. */
	private static String stoppedService() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return "http://localhost:" + socket.getLocalPort();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ServiceCallProperties.class)
	static class Settings {
	}

}
