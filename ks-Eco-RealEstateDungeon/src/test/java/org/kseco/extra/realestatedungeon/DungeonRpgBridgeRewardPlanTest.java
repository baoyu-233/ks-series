package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DungeonRpgBridgeRewardPlanTest {

    @Test
    void rewardKeysAreStableUniqueAndContentSensitive() {
        DungeonRpgBridge bridge = new DungeonRpgBridge(null);
        String config = """
                {
                  "money": 100,
                  "commands": ["ksrpg proof grant {player} clear"],
                  "mythicItems": [{"id":"Token","amount":2,"chance":0.5}],
                  "mmoitems": [{"type":"MATERIAL","id":"SCRAP","amount":3}]
                }
                """;

        List<DungeonRpgBridge.RewardGrant> first = bridge.parseRewardGrants(config);
        List<DungeonRpgBridge.RewardGrant> second = bridge.parseRewardGrants(config);

        assertEquals(4, first.size());
        assertEquals(first.stream().map(DungeonRpgBridge.RewardGrant::rewardKey).toList(),
                second.stream().map(DungeonRpgBridge.RewardGrant::rewardKey).toList());
        assertEquals(first.size(), new HashSet<>(first.stream()
                .map(DungeonRpgBridge.RewardGrant::rewardKey).toList()).size());

        String changed = config.replace("\"money\": 100", "\"money\": 101");
        assertNotEquals(first.getFirst().rewardKey(),
                bridge.parseRewardGrants(changed).getFirst().rewardKey());
    }

    @Test
    void invalidRewardJsonFailsBeforeAnyGrantCanBeClaimed() {
        DungeonRpgBridge bridge = new DungeonRpgBridge(null);
        assertThrows(RuntimeException.class, () -> bridge.parseRewardGrants("[1,2,3]"));
    }

    @Test
    void proofGrantCommandsExposeTheirActualTargetAndProof() {
        DungeonRpgBridge.ProofGrantCommand command = DungeonRpgBridge.parseProofGrantCommand(
                "/ksrpg proof grant Alice frostbound_clear");

        assertEquals("Alice", command.playerName());
        assertEquals("frostbound_clear", command.proofId());
        assertEquals(null, DungeonRpgBridge.parseProofGrantCommand("say proof grant Alice frostbound_clear"));
    }

    @Test
    void skippedProofGrantStaysUnresolvedWhileOptionalLootCanComplete() {
        DungeonRpgBridge bridge = new DungeonRpgBridge(null);
        DungeonRpgBridge.RewardGrant proof = bridge.parseRewardGrants("""
                {"commands":[{"command":"ksrpg proof grant {player} clear","chance":0}]}
                """).getFirst();
        DungeonRpgBridge.RewardGrant optional = bridge.parseRewardGrants("""
                {"commands":[{"command":"say optional","chance":0}]}
                """).getFirst();

        assertEquals(DungeonRpgBridge.DeliveryOutcome.REVIEW_REQUIRED,
                DungeonRpgBridge.chanceMissOutcome(proof));
        assertEquals(DungeonRpgBridge.DeliveryOutcome.SKIPPED,
                DungeonRpgBridge.chanceMissOutcome(optional));
    }
}
