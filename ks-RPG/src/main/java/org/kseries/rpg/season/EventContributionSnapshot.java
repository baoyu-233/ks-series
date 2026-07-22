package org.kseries.rpg.season;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventContributionSnapshot(
        String runId,
        String seasonId,
        String eventId,
        String regionId,
        String arenaId,
        long sequence,
        long capturedAt,
        Map<UUID, Integer> playerScores
) {
    public EventContributionSnapshot {
        runId = SeasonIds.require(runId, "event run id");
        seasonId = SeasonIds.require(seasonId, "season id");
        eventId = SeasonIds.require(eventId, "event id");
        regionId = SeasonIds.require(regionId, "region id");
        arenaId = SeasonIds.require(arenaId, "arena id");
        if (sequence < 0 || capturedAt < 0) {
            throw new IllegalArgumentException("snapshot sequence and timestamp must not be negative");
        }
        Objects.requireNonNull(playerScores, "playerScores");
        Map<UUID, Integer> copy = new LinkedHashMap<>();
        playerScores.forEach((playerId, score) -> {
            Objects.requireNonNull(playerId, "player score UUID");
            if (score == null || score < 0) {
                throw new IllegalArgumentException("player score must not be negative");
            }
            copy.put(playerId, score);
        });
        playerScores = Map.copyOf(copy);
    }
}
