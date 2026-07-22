package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonBossTrackingTest {

    @Test
    void completionRequiresEveryRegisteredBossToBeDead() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> bosses = Set.of(first, second);

        assertFalse(DungeonInstanceManager.allRegisteredBossesDead(bosses, uuid -> uuid.equals(second)));
        assertTrue(DungeonInstanceManager.allRegisteredBossesDead(bosses, uuid -> false));
        assertFalse(DungeonInstanceManager.allRegisteredBossesDead(Set.of(), uuid -> false));
    }
}
