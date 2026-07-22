package org.kseries.rpg;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

/** Maintains an independent round-robin cursor for each configured drop table. */
final class DropRecipientRotation {
    private final Map<String, Long> cursors = new HashMap<>();

    Optional<UUID> next(String tableId, List<UUID> eligiblePlayers) {
        if (tableId == null || tableId.isBlank()) {
            throw new IllegalArgumentException("tableId must not be blank");
        }
        LinkedHashSet<UUID> uniquePlayers = new LinkedHashSet<>();
        for (UUID playerId : eligiblePlayers) {
            if (playerId != null) uniquePlayers.add(playerId);
        }
        if (uniquePlayers.isEmpty()) return Optional.empty();

        List<UUID> orderedPlayers = List.copyOf(uniquePlayers);
        long cursor = cursors.getOrDefault(tableId, 0L);
        UUID selected = orderedPlayers.get((int) Math.floorMod(cursor, orderedPlayers.size()));
        cursors.put(tableId, cursor == Long.MAX_VALUE ? 0L : cursor + 1L);
        return Optional.of(selected);
    }
}
