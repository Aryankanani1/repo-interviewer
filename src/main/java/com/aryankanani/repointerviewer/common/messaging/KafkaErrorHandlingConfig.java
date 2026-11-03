package com.aryankanani.repointerviewer.common.messaging;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What happens when a {@code @KafkaListener} throws (task 2.4, LLD §13.3). Boot gives this {@link DefaultErrorHandler}
 * to every listener container.
 *
 * <ul>
 * <li><b>Retry</b> only a {@link RetryableEventException}: the record is delivered again after 1 s, 2 s, 4 s
 * ({@code app.consumer-retry}). The partition waits meanwhile, so a project's later events stay behind it.</li>
 * <li><b>Everything else</b> is not retried ({@code defaultFalse}): an {@link InvalidEventException} or a bug would
 * fail the same way again.</li>
 * <li><b>Then</b> the record goes to {@link Topics#dlt} with the same key and partition, plus headers that name the
 * original topic and the exception, and its offset is committed so the partition moves on.</li>
 * </ul>
 *
 * Each {@link RetryListener} bean hears about every failed delivery and every record sent to the DLT; the
 * {@code project} module uses one to mark the project FAILED, so this class does not need to know about projects.
 */
@Configuration
@Profile("kafka")
class KafkaErrorHandlingConfig {

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka, AppProperties properties,
			ObjectProvider<RetryListener> retryListeners) {
		AppProperties.ConsumerRetry retry = properties.consumerRetry();
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
		backOff.setInitialInterval(retry.initialBackoff().toMillis());
		backOff.setMultiplier(2.0);
		DeadLetterPublishingRecoverer toDlt = new DeadLetterPublishingRecoverer(kafka,
				(record, ex) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));
		DefaultErrorHandler handler = new DefaultErrorHandler(toDlt, backOff);
		handler.defaultFalse();
		handler.addRetryableExceptions(RetryableEventException.class);
		handler.setRetryListeners(retryListeners.orderedStream().toArray(RetryListener[]::new));
		return handler;
	}

}
