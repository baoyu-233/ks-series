package org.kseries.rpg.season;

import java.util.Map;
import java.util.UUID;

public record EventContributionState(
        String runId,
        String seasonId,
        String eventId,
        String regionId,
        String arenaId,
        long lastSequence,
        long updatedAt,
        Map<UUID, Integer> playerScores
) {
    public EventContributionState {
        runId = SeasonIds.require(runId, "event run id");
        seasonId = SeasonIds.require(seasonId, "season id");
        eventId = SeasonIds.require(eventId, "event id");
        regionId = SeasonIds.require(regionId, "region id");
        arenaId = SeasonIds.require(arenaId, "arena id");
        if (lastSequence < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("event state values must not be negative");
        }
        playerScores = Map.copyOf(playerScores);
    }
}
