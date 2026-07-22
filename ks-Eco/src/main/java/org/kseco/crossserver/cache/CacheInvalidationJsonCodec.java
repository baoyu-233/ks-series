package org.kseco.crossserver.cache;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable JSON schema for cache invalidation messages. */
public final class CacheInvalidationJsonCodec {
    public static final int SCHEMA_VERSION = 1;

    public String encode(CacheInvalidationMessage message) {
        CacheInvalidationMessage checked = Objects.requireNonNull(message, "message");
        JsonObject version = new JsonObject();
        version.addProperty("epochMillis", checked.version().epochMillis());
        version.addProperty("logicalCounter", checked.version().logicalCounter());
        version.addProperty("originNodeId", checked.version().originNodeId());
        version.addProperty("originInstanceId", checked.version().originInstanceId().toString());

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("eventId", checked.eventId().toString());
        root.addProperty("namespace", checked.namespace());
        root.addProperty("key", checked.key());
        root.add("version", version);
        root.addProperty("createdAtEpochMillis", checked.createdAtEpochMillis());
        return root.toString();
    }

    public byte[] encodeUtf8(CacheInvalidationMessage message) {
        return encode(message).getBytes(StandardCharsets.UTF_8);
    }

    public CacheInvalidationMessage decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonObject root = requireObject(JsonParser.parseString(json), "payload");
            int schemaVersion = requireInt(root, "schemaVersion");
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported cache invalidation schema: " + schemaVersion);
            }

            UUID eventId = requireUuid(root, "eventId");
            CacheKey target = new CacheKey(requireString(root, "namespace"), requireString(root, "key"));
            JsonObject versionJson = requireObject(require(root, "version"), "version");
            CacheVersionStamp version = new CacheVersionStamp(
                    requireLong(versionJson, "epochMillis"),
                    requireLong(versionJson, "logicalCounter"),
                    requireString(versionJson, "originNodeId"),
                    requireUuid(versionJson, "originInstanceId")
            );
            return new CacheInvalidationMessage(
                    eventId,
                    target,
                    version,
                    requireLong(root, "createdAtEpochMillis")
            );
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException("invalid cache invalidation JSON", exception);
        }
    }

    public CacheInvalidationMessage decodeUtf8(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return decode(new String(payload, StandardCharsets.UTF_8));
    }

    private static JsonElement require(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("missing JSON field: " + field);
        }
        return value;
    }

    private static JsonObject requireObject(JsonElement element, String field) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = require(object, field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a JSON string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static long requireLong(JsonObject object, String field) {
        JsonElement element = require(object, field);
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be a JSON number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException(field + " must be a JSON number");
        }
        return primitive.getAsLong();
    }

    private static int requireInt(JsonObject object, String field) {
        long value = requireLong(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside the integer range");
        }
        return (int) value;
    }

    private static UUID requireUuid(JsonObject object, String field) {
        try {
            return UUID.fromString(requireString(object, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
