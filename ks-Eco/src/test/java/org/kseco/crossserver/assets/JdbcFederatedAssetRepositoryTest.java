package org.kseco.crossserver.assets;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFederatedAssetRepositoryTest {
    @Test
    void sqliteSchemaAndProtocol() throws Exception {
        Path database = Files.createTempFile("ks-fed-assets-", ".db");
        try {
            verify("jdbc:sqlite:" + database.toAbsolutePath());
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void mysqlModeSchemaAndProtocol() throws Exception {
        verify(h2("MySQL"));
    }

    @Test
    void mariaDbModeSchemaAndProtocol() throws Exception {
        verify(h2("MariaDB"));
    }

    @Test
    void postgreSqlModeSchemaAndProtocol() throws Exception {
        verify(h2("PostgreSQL"));
    }

    private static void verify(String url) throws Exception {
        JdbcFederatedAssetRepository repository = new JdbcFederatedAssetRepository(
                () -> DriverManager.getConnection(url));
        repository.initialize();
        repository.initialize();

        AssetSource source = new AssetSource("survival", "world", "minecraft:overworld");
        byte[] firstPayload = "map-one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FederatedAsset firstAsset = new FederatedAsset("property:house-1", "house-1", "PROPERTY",
                "player:one", 1, 100_00, 1, 1_000, source);
        FederatedSnapshot.Bundle first = FederatedSnapshotCodec.encode("snapshot-1", FederatedSnapshot.Kind.PROPERTY,
                source, 1, "application/x-kseco-property-v1", firstPayload, List.of(firstAsset),
                1_000, 100, 1_024);
        assertEquals(FederatedAssetRepository.PublishResult.PUBLISHED, repository.publish(first, "boot-a"));
        assertEquals(FederatedAssetRepository.PublishResult.ALREADY_PUBLISHED, repository.publish(first, "boot-a"));

        FederatedSnapshot.ReadResult fresh = repository.readLatest(FederatedSnapshot.Kind.PROPERTY, source,
                1_050, 100).orElseThrow();
        assertFalse(fresh.stale());
        assertFalse(fresh.offline());
        assertArrayEquals(firstPayload, FederatedSnapshotCodec.decode(fresh.bundle()));
        assertEquals(1, repository.queryAssets(new FederatedAssetRepository.Filter(
                "survival", "world", "minecraft:overworld", "PROPERTY", null)).size());

        FederatedSnapshot.ReadResult expired = repository.readLatest(FederatedSnapshot.Kind.PROPERTY, source,
                1_201, 100).orElseThrow();
        assertTrue(expired.stale());
        assertTrue(expired.offline());

        FederatedSnapshot.Bundle staleRevision = FederatedSnapshotCodec.encode("snapshot-old",
                FederatedSnapshot.Kind.PROPERTY, source, 0, "application/x-kseco-property-v1",
                "old".getBytes(), List.of(), 2_000, 100, 1_024);
        assertEquals(FederatedAssetRepository.PublishResult.STALE_REJECTED,
                repository.publish(staleRevision, "boot-a"));

        FederatedSnapshot.Bundle second = FederatedSnapshotCodec.encode("snapshot-2",
                FederatedSnapshot.Kind.PROPERTY, source, 2, "application/x-kseco-property-v1",
                "map-two".getBytes(), List.of(), 2_000, 100, 1_024);
        assertEquals(FederatedAssetRepository.PublishResult.PUBLISHED, repository.publish(second, "boot-a"));
        assertTrue(repository.queryAssets(null).isEmpty());
    }

    private static String h2(String mode) {
        return "jdbc:h2:mem:fed_" + mode.toLowerCase() + "_" + UUID.randomUUID()
                + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }
}
