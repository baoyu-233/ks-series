package org.kseco.extra.realestate;

import org.junit.jupiter.api.Test;
import org.kseco.crossserver.assets.FederatedSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedPropertySnapshotFactoryTest {
    @Test
    void buildsPropertySnapshotFromFrozenDtosWithExactMinorUnits() {
        Map<String, Object> house = Map.of(
                "id", "house-1", "world", "world", "ownerType", "PLAYER",
                "ownerId", "player-1", "showcasePrice", 123.456);
        Map<String, Object> ready = Map.of(
                "status", "READY", "generatedAt", 5_000L, "blocks", List.of(Map.of("x", 0, "mat", "STONE")));

        var prepared = FederatedPropertySnapshotFactory.create("survival", "minecraft:overworld",
                List.of(new FederatedPropertySnapshotFactory.HouseSnapshot(house, ready)), 5_000L, 5_000L);

        assertEquals(FederatedSnapshot.Kind.PROPERTY, prepared.kind());
        assertEquals("survival", prepared.source().nodeId());
        assertEquals(12_346L, prepared.assets().getFirst().valueMinor());
        assertEquals("PLAYER:player-1".toLowerCase(), prepared.assets().getFirst().ownerKey());
        assertTrue(new String(prepared.payload(), StandardCharsets.UTF_8).contains("READY"));
    }

    @Test
    void oneWorldSnapshotContainsEveryPreparedHouseInsteadOfReplacingPreviousHouse() {
        Map<String, Object> first = Map.of("id", "house-1", "world", "world", "ownerId", "one");
        Map<String, Object> second = Map.of("id", "house-2", "world", "world", "ownerId", "two");
        var prepared = FederatedPropertySnapshotFactory.create("survival", "minecraft:overworld", List.of(
                new FederatedPropertySnapshotFactory.HouseSnapshot(first,
                        Map.of("houseId", "house-1", "status", "READY")),
                new FederatedPropertySnapshotFactory.HouseSnapshot(second,
                        Map.of("houseId", "house-2", "status", "READY"))), 7_000L, 7_000L);

        assertEquals(2, prepared.assets().size());
        assertEquals(List.of("house-1", "house-2"),
                prepared.assets().stream().map(asset -> asset.sourceAssetId()).toList());
        String json = new String(prepared.payload(), StandardCharsets.UTF_8);
        assertTrue(json.contains("house-1"));
        assertTrue(json.contains("house-2"));
    }

    @Test
    void rejectsInvalidPricesWithoutOverflowingProjection() {
        assertEquals(0, FederatedPropertySnapshotFactory.priceMinor(-1));
        assertEquals(0, FederatedPropertySnapshotFactory.priceMinor(Double.POSITIVE_INFINITY));
        assertEquals(0, FederatedPropertySnapshotFactory.priceMinor(Double.MAX_VALUE));
    }

    @Test
    void freezesReadyEnvelopeWhilePreservingNullableOptionalFields() {
        Map<String, Object> house = new LinkedHashMap<>();
        house.put("id", "house-null");
        house.put("world", "world");
        house.put("ownerId", null);
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("status", "READY");
        ready.put("error", null);
        var frozen = new FederatedPropertySnapshotFactory.HouseSnapshot(house, ready);
        ready.put("status", "MUTATED");

        var prepared = FederatedPropertySnapshotFactory.create("survival", "minecraft:overworld",
                List.of(frozen), 8_000L, 8_000L);
        String json = new String(prepared.payload(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\":\"READY\""));
        assertFalse(json.contains("MUTATED"));
    }
}
