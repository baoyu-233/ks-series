package org.kseco.crossserver.assets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedAccessPolicyTest {
    private static final AssetSource SURVIVAL = new AssetSource("survival", "world", "minecraft:overworld");
    private static final AssetSource NETHER = new AssetSource("survival", "world_nether", "minecraft:the_nether");

    @Test
    void defaultsToDenyAndDenylistWinsOverBroadAllow() {
        assertFalse(FederatedAccessPolicy.denyAll().decide(FederatedCapability.MAP_VIEW, SURVIVAL).allowed());

        FederatedAccessPolicy policy = FederatedAccessPolicy.fromMap(Map.of("map-view", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("*"), List.of("*")),
                "deny", axes(List.of(), List.of("world_nether"), List.of())
        )));
        assertTrue(policy.decide(FederatedCapability.MAP_VIEW, SURVIVAL).allowed());
        assertEquals("denylist", policy.decide(FederatedCapability.MAP_VIEW, NETHER).reason());
    }

    @Test
    void everyAxisMustBeExplicitlyAllowedAndTransferChecksBothEnds() {
        FederatedAccessPolicy incomplete = FederatedAccessPolicy.fromMap(Map.of("asset-aggregate", Map.of(
                "enabled", true,
                "allow", axes(List.of("survival"), List.of("world"), List.of()),
                "deny", axes(List.of(), List.of(), List.of())
        )));
        assertEquals("not-allowlisted",
                incomplete.decide(FederatedCapability.ASSET_AGGREGATE, SURVIVAL).reason());

        FederatedAccessPolicy transfer = FederatedAccessPolicy.fromMap(Map.of("transfer", Map.of(
                "enabled", true,
                "allow", axes(List.of("survival", "rpg"), List.of("world"), List.of("minecraft:overworld")),
                "deny", axes(List.of("rpg"), List.of(), List.of())
        )));
        AssetSource rpg = new AssetSource("rpg", "world", "minecraft:overworld");
        assertFalse(transfer.decideTransfer(SURVIVAL, rpg).allowed());
    }

    @Test
    void failedReloadLeavesPreviousPolicyAndGenerationUntouched() {
        FederatedPolicyManager manager = new FederatedPolicyManager();
        long generation = manager.reload(Map.of("property-trade", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("world"), List.of("minecraft:overworld")),
                "deny", axes(List.of(), List.of(), List.of())
        )));
        assertTrue(manager.current().decide(FederatedCapability.PROPERTY_TRADE, SURVIVAL).allowed());

        assertThrows(IllegalArgumentException.class,
                () -> manager.reload(Map.of("property-trade", Map.of("enabled", "yes"))));
        assertEquals(generation, manager.generation());
        assertTrue(manager.current().decide(FederatedCapability.PROPERTY_TRADE, SURVIVAL).allowed());
    }

    private static Map<String, Object> axes(List<String> servers, List<String> worlds, List<String> dimensions) {
        return Map.of("servers", servers, "worlds", worlds, "dimensions", dimensions);
    }
}
