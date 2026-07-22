package org.kseco.crossserver.transport;

import org.junit.jupiter.api.Test;
import org.kseco.crossserver.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatabaseTransportStoreTest {

    @Test
    void publishSequenceDeliversLateEventsAndDoesNotSkipFutureGaps() throws Exception {
        String url = "jdbc:sqlite:file:transport-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        try (Connection keeper = DriverManager.getConnection(url)) {
            JdbcDatabaseTransportStore store = new JdbcDatabaseTransportStore(
                    () -> DriverManager.getConnection(url), SqlDialect.SQLITE);
            store.initialize();

            TransportEvent first = event("first", 100L, 100L);
            assertEquals(TransportPublishReceipt.Disposition.PUBLISHED, store.publish(first).disposition());
            assertEquals(TransportPublishReceipt.Disposition.ALREADY_PRESENT,
                    store.publish(event("first", 100L, 100L)).disposition());

            PollCursor cursor = PollCursor.initial();
            PollBatch firstBatch = store.poll(new PollRequest("consumer", "server-a", cursor, 10, 100L));
            assertEquals(1, firstBatch.events().size());
            assertTrue(store.advanceCursor("consumer", cursor, firstBatch.nextCursor()));
            cursor = firstBatch.nextCursor();

            store.publish(event("late", 10L, 10L));
            PollBatch lateBatch = store.poll(new PollRequest("consumer", "server-a", cursor, 10, 100L));
            assertEquals("late", lateBatch.events().getFirst().eventId());
            assertTrue(store.advanceCursor("consumer", cursor, lateBatch.nextCursor()));
            cursor = lateBatch.nextCursor();

            store.publish(event("future", 900L, 1_000L));
            store.publish(event("ready-behind-future", 100L, 100L));
            PollBatch blocked = store.poll(new PollRequest("consumer", "server-a", cursor, 10, 100L));
            assertTrue(blocked.events().isEmpty());
            assertEquals(cursor, blocked.nextCursor());

            PollBatch released = store.poll(new PollRequest("consumer", "server-a", cursor, 10, 1_000L));
            assertEquals(java.util.List.of("future", "ready-behind-future"),
                    released.events().stream().map(TransportEvent::eventId).toList());
        }
    }

    private static TransportEvent event(String id, long occurredAt, long availableAt) {
        return new TransportEvent(id, "topic", "server-a", TransportEvent.BROADCAST_TARGET,
                occurredAt, availableAt, 0L, "application/json", new byte[]{1, 2, 3}, Map.of("v", "1"));
    }
}
