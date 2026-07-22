package org.kseco.crossserver;

import java.time.Instant;

/**
 * Database lease with a monotonically increasing fencing token. Callers must
 * include the token in every guarded write so a paused former owner cannot act.
 */
public record LeaseRecord(
        String leaseName,
        String ownerId,
        long fencingToken,
        Instant expiresAt,
        Instant updatedAt
) {
    public LeaseRecord {
        leaseName = CrossServerValidation.identifier(leaseName, "leaseName", 128);
        ownerId = CrossServerValidation.identifier(ownerId, "ownerId", 192);
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be at least 1");
        }
        expiresAt = CrossServerValidation.instant(expiresAt, "expiresAt");
        updatedAt = CrossServerValidation.instant(updatedAt, "updatedAt");
        CrossServerValidation.notBefore(updatedAt, expiresAt, "expiresAt");
    }

    public boolean isHeldBy(String expectedOwnerId, Instant instant) {
        return ownerId.equals(expectedOwnerId) && expiresAt.isAfter(CrossServerValidation.instant(instant, "instant"));
    }
}
