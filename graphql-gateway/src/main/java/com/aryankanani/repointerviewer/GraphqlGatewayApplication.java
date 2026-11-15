package com.aryankanani.repointerviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * graphql-gateway (task 4.1, DECISIONS.md D-026): the {@code api} module, the public GraphQL API in front of
 * analysis-service and interview-service, and the fan-out of their events to the {@code interviewEvents}
 * subscriptions. No database and no model calls of its own. Its {@code app.*} settings, only the Kafka consumers'
 * retries: {@link AppPropertiesConfiguration}.
 */
@SpringBootApplication
public class GraphqlGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GraphqlGatewayApplication.class, args);
	}

}
