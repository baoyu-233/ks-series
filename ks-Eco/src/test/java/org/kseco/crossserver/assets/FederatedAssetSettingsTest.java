package org.kseco.crossserver.assets;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedAssetSettingsTest {
    @Test
    void settingsReloadIsAtomicAndDefaultsAreSafe() {
        FederatedAssetSettingsManager manager = new FederatedAssetSettingsManager();
        assertFalse(manager.current().enabled());
        assertFalse(manager.current().policy().decide(FederatedCapability.MAP_VIEW,
                new AssetSource("survival", "world", "minecraft:overworld")).allowed());

        long generation = manager.reload(enabledSettings());
        assertTrue(manager.current().enabled());
        assertEquals(1_024, manager.current().fragmentBytes());
        assertEquals(2_048, manager.current().maxSnapshotBytes());
        assertThrows(IllegalArgumentException.class,
                () -> manager.reload(Map.of("enabled", true, "fragment-bytes", 16)));
        assertEquals(generation, manager.generation());
        assertEquals(1_024, manager.current().fragmentBytes());
        assertEquals(2_048, manager.current().maxSnapshotBytes());
    }

    @Test
    void boundedDecoderAndPublisherRejectOversizedSnapshots() {
        AssetSource source = new AssetSource("survival", "world", "minecraft:overworld");
        byte[] payload = new byte[2_049];
        FederatedSnapshot.Bundle bundle = FederatedSnapshotCodec.encode("bounded-1", FederatedSnapshot.Kind.MAP,
                source, 1, "application/json", payload, List.of(), 1_000, 10_000, 1_024);
        assertThrows(IllegalArgumentException.class, () -> FederatedSnapshotCodec.decode(bundle, 2_048));
    }

    @Test
    void publisherCompressesAndPersistsImmutablePreparedBytesOnExecutor() throws Exception {
        String url = "jdbc:h2:mem:fed_publisher_" + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcFederatedAssetRepository repository = new JdbcFederatedAssetRepository(
                () -> DriverManager.getConnection(url));
        repository.initialize();
        FederatedAssetSettingsManager settings = new FederatedAssetSettingsManager();
        settings.reload(enabledSettings());
        AssetSource source = new AssetSource("survival", "world", "minecraft:overworld");
        byte[] mutable = "immutable-world-snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (var executor = Executors.newSingleThreadExecutor()) {
            FederatedSnapshotPublisher publisher = new FederatedSnapshotPublisher(
                    repository, settings, executor, "boot-a");
            var prepared = new FederatedSnapshotPublisher.PreparedSnapshot("async-1",
                    FederatedSnapshot.Kind.MAP, source, 1, "application/x-kseco-map-v1",
                    mutable, List.of(), 1_000);
            mutable[0] = 'X';
            assertEquals(FederatedAssetRepository.PublishResult.PUBLISHED,
                    publisher.publishAsync(prepared).get());
        }

        FederatedSnapshot.ReadResult result = repository.readLatest(FederatedSnapshot.Kind.MAP, source,
                1_050, 120_000).orElseThrow();
        assertArrayEquals("immutable-world-snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                FederatedSnapshotCodec.decode(result.bundle()));
    }

    private static Map<String, Object> enabledSettings() {
        return Map.of(
                "enabled", true,
                "snapshot-ttl-seconds", 300,
                "stale-after-seconds", 300,
                "offline-after-seconds", 120,
                "fragment-bytes", 1_024,
                "max-snapshot-bytes", 2_048,
                "policies", Map.of("map-view", Map.of(
                        "enabled", true,
                        "allow", Map.of("servers", List.of("*"), "worlds", List.of("*"),
                                "dimensions", List.of("*")),
                        "deny", Map.of("servers", List.of(), "worlds", List.of("world_private"),
                                "dimensions", List.of())
                ))
        );
    }
}
