package org.kseco.crossserver;

import java.time.Instant;

/** Immutable liveness snapshot for one logical server and its current process. */
public record ServerHeartbeat(
        String serverId,
        String instanceId,
        Instant startedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        String metadataJson
) {
    public ServerHeartbeat {
        serverId = CrossServerValidation.identifier(serverId, "serverId", 64);
        instanceId = CrossServerValidation.identifier(instanceId, "instanceId", 96);
        startedAt = CrossServerValidation.instant(startedAt, "startedAt");
        lastSeenAt = CrossServerValidation.instant(lastSeenAt, "lastSeenAt");
        expiresAt = CrossServerValidation.instant(expiresAt, "expiresAt");
        CrossServerValidation.notBefore(startedAt, lastSeenAt, "lastSeenAt");
        CrossServerValidation.notBefore(lastSeenAt, expiresAt, "expiresAt");
        metadataJson = CrossServerValidation.text(metadataJson, "metadataJson", 65_536);
    }

    public boolean isAliveAt(Instant instant) {
        Instant checked = CrossServerValidation.instant(instant, "instant");
        return !lastSeenAt.isAfter(checked) && expiresAt.isAfter(checked);
    }
}
