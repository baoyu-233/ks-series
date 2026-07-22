package org.kseco.crossserver.assets;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedAssetServiceTest {
    @Test
    void filtersScopesDeduplicatesReplicasAndExcludesOfflineNodes() throws Exception {
        String url = "jdbc:h2:mem:fed_service_" + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcFederatedAssetRepository repository = new JdbcFederatedAssetRepository(
                () -> DriverManager.getConnection(url));
        repository.initialize();

        AssetSource survival = new AssetSource("survival", "world", "minecraft:overworld");
        AssetSource rpg = new AssetSource("rpg", "world", "minecraft:overworld");
        publish(repository, "snapshot-a", survival, 1, 1_000, 1, 100);
        publish(repository, "snapshot-b", rpg, 2, 1_200, 2, 200);

        FederatedPolicyManager policies = new FederatedPolicyManager();
        policies.reload(Map.of("asset-aggregate", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("world"), List.of("minecraft:overworld")),
                "deny", axes(List.of(), List.of(), List.of())
        ), "property-trade", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("world"), List.of("minecraft:overworld")),
                "deny", axes(List.of(), List.of(), List.of())
        ), "transfer", Map.of(
                "enabled", true,
                "allow", axes(List.of("survival"), List.of("world"), List.of("minecraft:overworld")),
                "deny", axes(List.of(), List.of(), List.of())
        )));
        FederatedAssetService service = new FederatedAssetService(repository, policies, 10_000, 500);

        FederatedAssetService.Aggregate fresh = service.aggregate(FederatedAssetService.Query.aggregateAll(), 1_250);
        assertEquals(1, fresh.distinctAssets());
        assertEquals(2, fresh.quantity());
        assertEquals(200, fresh.valueMinor());

        FederatedAssetService.Query survivalOnly = new FederatedAssetService.Query(
                FederatedCapability.ASSET_AGGREGATE, "survival", null, null, null, null, false, false);
        assertEquals(1, service.query(survivalOnly, 1_250).getFirst().asset().quantity());

        repository.heartbeat("survival", "boot-a", 1_900);
        FederatedAssetService.Aggregate withoutOfflineReplica = service.aggregate(
                FederatedAssetService.Query.aggregateAll(), 2_000);
        assertEquals(1, withoutOfflineReplica.quantity());
        assertFalse(service.canTransfer(survival, rpg));
        assertTrue(service.canTransfer(survival, survival));

        policies.reload(Map.of("asset-aggregate", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("*"), List.of("*")),
                "deny", axes(List.of(), List.of(), List.of()))));
        assertTrue(service.query(FederatedAssetService.Query.aggregateAll(), 2_000).isEmpty(),
                "property assets require both aggregate and property policy");
    }

    @Test
    void snapshotDiscoveryAndReadHonorHotReloadedWorldDenyPolicy() throws Exception {
        String url = "jdbc:h2:mem:fed_snapshot_service_" + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcFederatedAssetRepository repository = new JdbcFederatedAssetRepository(
                () -> DriverManager.getConnection(url));
        repository.initialize();
        AssetSource publicWorld = new AssetSource("survival", "world", "minecraft:overworld");
        AssetSource privateWorld = new AssetSource("survival", "world_private", "minecraft:overworld");
        publishMap(repository, "map-public", publicWorld, 1, 1_000);
        publishMap(repository, "map-private", privateWorld, 1, 1_000);

        FederatedAssetSettingsManager settings = new FederatedAssetSettingsManager();
        settings.reload(Map.of("enabled", true, "policies", Map.of("map-view", Map.of(
                "enabled", true,
                "allow", axes(List.of("*"), List.of("*"), List.of("*")),
                "deny", axes(List.of(), List.of("world_private"), List.of())))));
        FederatedAssetService service = new FederatedAssetService(repository, settings);

        assertEquals(1, service.listSnapshotHeads(FederatedSnapshot.Kind.MAP, 1_100, false, false).size());
        assertTrue(service.readSnapshot(FederatedSnapshot.Kind.MAP, publicWorld, 1_100, false, false).isPresent());
        assertTrue(service.readSnapshot(FederatedSnapshot.Kind.MAP, privateWorld, 1_100, true, true).isEmpty());

        settings.reload(Map.of("enabled", false));
        assertTrue(service.listSnapshotHeads(FederatedSnapshot.Kind.MAP, 1_100, true, true).isEmpty());
        assertTrue(service.query(FederatedAssetService.Query.aggregateAll(), 1_100).isEmpty());
    }

    private static void publish(JdbcFederatedAssetRepository repository, String snapshotId, AssetSource source,
                                long revision, long time, long quantity, long value) throws Exception {
        FederatedAsset asset = new FederatedAsset("property:shared-house", "house-local", "PROPERTY",
                "player:one", quantity, value, revision, time, source);
        FederatedSnapshot.Bundle bundle = FederatedSnapshotCodec.encode(snapshotId, FederatedSnapshot.Kind.ASSET,
                source, revision, "application/x-kseco-assets-v1", snapshotId.getBytes(), List.of(asset),
                time, 10_000, 1_024);
        assertEquals(FederatedAssetRepository.PublishResult.PUBLISHED, repository.publish(bundle, "boot-" + source.nodeId()));
    }

    private static void publishMap(JdbcFederatedAssetRepository repository, String snapshotId, AssetSource source,
                                   long revision, long time) throws Exception {
        FederatedSnapshot.Bundle bundle = FederatedSnapshotCodec.encode(snapshotId, FederatedSnapshot.Kind.MAP,
                source, revision, "application/json", "{}".getBytes(), List.of(), time, 10_000, 1_024);
        assertEquals(FederatedAssetRepository.PublishResult.PUBLISHED, repository.publish(bundle, "boot-a"));
    }

    private static Map<String, Object> axes(List<String> servers, List<String> worlds, List<String> dimensions) {
        return Map.of("servers", servers, "worlds", worlds, "dimensions", dimensions);
    }
}
