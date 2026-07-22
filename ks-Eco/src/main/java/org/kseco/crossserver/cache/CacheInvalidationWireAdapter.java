package org.kseco.crossserver.cache;

import org.kseco.crossserver.CrossServerEventEnvelope;
import org.kseco.crossserver.transport.TransportEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bridges cache messages to both ks-Eco cross-server wire contracts. */
public final class CacheInvalidationWireAdapter {
    public static final String TOPIC = "ks-eco.cache-invalidation";
    public static final String EVENT_TYPE = "cache.invalidate";
    public static final String CONTENT_TYPE = "application/vnd.ks-eco.cache-invalidation+json";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_SCHEMA_VERSION = "schemaVersion";
    public static final String HEADER_SOURCE_INSTANCE_ID = "sourceInstanceId";

    private final CacheInvalidationJsonCodec codec;

    public CacheInvalidationWireAdapter() {
        this(new CacheInvalidationJsonCodec());
    }

    public CacheInvalidationWireAdapter(CacheInvalidationJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public CrossServerEventEnvelope toEnvelope(CacheInvalidationMessage message) {
        CacheInvalidationMessage checked = Objects.requireNonNull(message, "message");
        return new CrossServerEventEnvelope(
                checked.eventId(),
                TOPIC,
                EVENT_TYPE,
                aggregateKey(checked.target()),
                checked.version().originNodeId(),
                checked.version().originInstanceId().toString(),
                codec.encode(checked),
                CacheInvalidationJsonCodec.SCHEMA_VERSION,
                Instant.ofEpochMilli(checked.createdAtEpochMillis())
        );
    }

    public CacheInvalidationMessage fromEnvelope(CrossServerEventEnvelope envelope) {
        CrossServerEventEnvelope checked = Objects.requireNonNull(envelope, "envelope");
        requireEqual(TOPIC, checked.topic(), "topic");
        requireEqual(EVENT_TYPE, checked.eventType(), "eventType");
        if (checked.schemaVersion() != CacheInvalidationJsonCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported envelope schema: " + checked.schemaVersion());
        }

        CacheInvalidationMessage message = codec.decode(checked.payloadJson());
        requireEqual(checked.eventId(), message.eventId(), "eventId");
        requireEqual(checked.sourceServerId(), message.version().originNodeId(), "sourceServerId");
        requireEqual(checked.sourceInstanceId(), message.version().originInstanceId().toString(), "sourceInstanceId");
        requireEqual(checked.occurredAt().toEpochMilli(), message.createdAtEpochMillis(), "occurredAt");
        return message;
    }

    public TransportEvent toTransportEvent(CacheInvalidationMessage message) {
        return toTransportEvent(message, Duration.ZERO);
    }

    /**
     * Converts a broadcast invalidation. A zero retention means the transport
     * event does not expire.
     */
    public TransportEvent toTransportEvent(CacheInvalidationMessage message, Duration retention) {
        CacheInvalidationMessage checked = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative");
        }

        long occurredAt = checked.createdAtEpochMillis();
        long expiresAt = retention.isZero() ? 0L : saturatingAdd(occurredAt, retention.toMillis());
        Map<String, String> headers = Map.of(
                HEADER_EVENT_TYPE, EVENT_TYPE,
                HEADER_SCHEMA_VERSION, Integer.toString(CacheInvalidationJsonCodec.SCHEMA_VERSION),
                HEADER_SOURCE_INSTANCE_ID, checked.version().originInstanceId().toString()
        );
        return new TransportEvent(
                checked.eventId().toString(),
                TOPIC,
                checked.version().originNodeId(),
                TransportEvent.BROADCAST_TARGET,
                occurredAt,
                occurredAt,
                expiresAt,
                CONTENT_TYPE,
                codec.encodeUtf8(checked),
                headers
        );
    }

    public CacheInvalidationMessage fromTransportEvent(TransportEvent event) {
        TransportEvent checked = Objects.requireNonNull(event, "event");
        requireEqual(TOPIC, checked.topic(), "topic");
        requireEqual(CONTENT_TYPE, checked.contentType(), "contentType");
        requireEqual(EVENT_TYPE, checked.headers().get(HEADER_EVENT_TYPE), HEADER_EVENT_TYPE);
        requireEqual(
                Integer.toString(CacheInvalidationJsonCodec.SCHEMA_VERSION),
                checked.headers().get(HEADER_SCHEMA_VERSION),
                HEADER_SCHEMA_VERSION
        );

        CacheInvalidationMessage message = codec.decodeUtf8(checked.payload());
        requireEqual(checked.eventId(), message.eventId().toString(), "eventId");
        requireEqual(checked.sourceServerId(), message.version().originNodeId(), "sourceServerId");
        requireEqual(
                checked.headers().get(HEADER_SOURCE_INSTANCE_ID),
                message.version().originInstanceId().toString(),
                HEADER_SOURCE_INSTANCE_ID
        );
        requireEqual(checked.occurredAtEpochMillis(), message.createdAtEpochMillis(), "occurredAt");
        return message;
    }

    private static String aggregateKey(CacheKey target) {
        String readable = target.namespace() + "/" + target.key();
        if (readable.length() <= 256) return readable;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(readable.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long saturatingAdd(long value, long increment) {
        if (increment < 0L || value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    private static void requireEqual(Object expected, Object actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(field + " does not match cache invalidation payload");
        }
    }
}
