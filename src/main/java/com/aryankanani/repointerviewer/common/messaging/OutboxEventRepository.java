package com.aryankanani.repointerviewer.common.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/**
	 * The relay's poll: unpublished events, oldest first ({@code id} breaks ties: UUIDv7 is time-ordered), locked
	 * until the caller's transaction ends. {@code SKIP LOCKED} passes over rows another relay holds instead of waiting
	 * for them, so two relays share the work and never send the same row at the same time. Served by the partial
	 * index {@code ix_outbox_unpublished}. Must run inside a transaction.
	 */
	@Query(value = """
			SELECT * FROM outbox_event
			WHERE published_at IS NULL
			ORDER BY created_at, id
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

}
