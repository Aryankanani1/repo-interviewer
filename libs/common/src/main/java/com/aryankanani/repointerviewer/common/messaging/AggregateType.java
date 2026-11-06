package com.aryankanani.repointerviewer.common.messaging;

/** What an outbox event is about. Its id is the Kafka key, so one aggregate's events stay in order. */
public enum AggregateType {
	PROJECT,
	SESSION
}
