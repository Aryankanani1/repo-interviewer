package com.aryankanani.repointerviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // only OutboxRelay is @Scheduled, and only with the kafka profile (task 2.2)
public class RepoInterviewerApplication {

	public static void main(String[] args) {
		SpringApplication.run(RepoInterviewerApplication.class, args);
	}

}
