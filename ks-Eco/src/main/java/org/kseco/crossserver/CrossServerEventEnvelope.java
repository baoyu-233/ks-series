package org.kseco.crossserver;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, transport-neutral event persisted in the shared database outbox.
 * The payload is an already-snapshotted JSON string and must never contain a
 * Bukkit object or another mutable live server object.
 */
public record CrossServerEventEnvelope(
        UUID eventId,
        String topic,
        String eventType,
        String aggregateKey,
        String sourceServerId,
        String sourceInstanceId,
        String payloadJson,
        int schemaVersion,
        Instant occurredAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_LENGTH = 1_048_576;

    public CrossServerEventEnvelope {
        eventId = Objects.requireNonNull(eventId, "eventId");
        topic = CrossServerValidation.identifier(topic, "topic", 128);
        eventType = CrossServerValidation.identifier(eventType, "eventType", 128);
        aggregateKey = CrossServerValidation.text(aggregateKey, "aggregateKey", 256);
        sourceServerId = CrossServerValidation.identifier(sourceServerId, "sourceServerId", 64);
        sourceInstanceId = CrossServerValidation.identifier(sourceInstanceId, "sourceInstanceId", 96);
        payloadJson = CrossServerValidation.text(payloadJson, "payloadJson", MAX_PAYLOAD_LENGTH);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        occurredAt = CrossServerValidation.instant(occurredAt, "occurredAt");
    }

    public static CrossServerEventEnvelope create(
            String topic,
            String eventType,
            String aggregateKey,
            String payloadJson,
            ServerInstance source,
            Clock clock
    ) {
        ServerInstance checkedSource = Objects.requireNonNull(source, "source");
        return new CrossServerEventEnvelope(
                UUID.randomUUID(),
                topic,
                eventType,
                aggregateKey,
                checkedSource.serverId(),
                checkedSource.instanceId(),
                payloadJson,
                CURRENT_SCHEMA_VERSION,
                Instant.now(Objects.requireNonNull(clock, "clock"))
        );
    }
}
