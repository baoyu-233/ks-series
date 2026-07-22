package org.kseries.rpg.season;

import java.util.Objects;

public record SeasonRecord(
        String id,
        String displayName,
        SeasonState state,
        long startsAt,
        long endsAt,
        String configHash,
        long createdAt,
        long updatedAt
) {
    public SeasonRecord {
        id = SeasonIds.require(id, "season id");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty()) throw new IllegalArgumentException("displayName must not be blank");
        state = Objects.requireNonNull(state, "state");
        configHash = Objects.requireNonNullElse(configHash, "").trim();
        if (startsAt < 0 || endsAt <= startsAt) {
            throw new IllegalArgumentException("season time range is invalid");
        }
        if (createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("season timestamps are invalid");
        }
    }

    SeasonRecord withState(SeasonState next, long changedAt) {
        return new SeasonRecord(id, displayName, next, startsAt, endsAt, configHash, createdAt, changedAt);
    }
}
