package org.kseries.rpg.season;

import java.util.Objects;
import java.util.UUID;

public record SeasonArchiveEntry(
        String seasonId,
        UUID playerId,
        long totalReputation,
        long totalScore,
        int regionCount,
        long archivedAt
) {
    public SeasonArchiveEntry {
        seasonId = SeasonIds.require(seasonId, "season id");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (totalReputation < 0 || totalScore < 0 || regionCount < 0 || archivedAt < 0) {
            throw new IllegalArgumentException("archive values must not be negative");
        }
    }
}
