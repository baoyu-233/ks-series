package org.kshwp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FederatedMapPublisherTest {
    @Test
    void encodesOnlyFrozenTileDataIntoVersionedEnvelope() {
        byte[] png = new byte[] {1, 2, 3, 4};
        byte[] payload = FederatedMapPublisher.encodeTilePayload(
                "world", "minecraft:overworld", -3, 7, 4, png, 123L);
        png[0] = 99;

        String json = new String(payload, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\":\"ks-hwp-map-tile/v1\""));
        assertTrue(json.contains("\"tileX\":-3"));
        assertEquals("AQIDBA==", JsonParser.parseString(json).getAsJsonObject().get("pngBase64").getAsString());
        assertFalse(json.contains("99"));
    }

    @Test
    void derivesStablePolicyCompatibleSourceIdsWithoutWorldAccess() {
        assertEquals("my_world", FederatedMapPublisher.canonicalId("My World"));
        assertEquals("minecraft:overworld", FederatedMapPublisher.dimensionId("world"));
        assertEquals("minecraft:the_nether", FederatedMapPublisher.dimensionId("world_nether"));
        assertEquals("minecraft:the_end", FederatedMapPublisher.dimensionId("world_the_end"));
    }

    @Test
    void bundleKeepsMultipleTilesForOneWorldSource() {
        byte[] payload = FederatedMapPublisher.encodeTileBundlePayload("world", "minecraft:overworld", List.of(
                new FederatedMapPublisher.TileData(1, 2, 4, new byte[]{1, 2}, 100L),
                new FederatedMapPublisher.TileData(3, 4, 4, new byte[]{3, 4}, 101L)), 101L);
        var root = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("ks-hwp-map-tile-bundle/v1", root.get("schema").getAsString());
        assertEquals(2, root.getAsJsonArray("tiles").size());
        assertEquals(1, root.getAsJsonArray("tiles").get(0).getAsJsonObject().get("tileX").getAsInt());
        assertEquals(3, root.getAsJsonArray("tiles").get(1).getAsJsonObject().get("tileX").getAsInt());
    }
}
