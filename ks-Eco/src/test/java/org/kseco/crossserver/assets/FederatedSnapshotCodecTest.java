package org.kseco.crossserver.assets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedSnapshotCodecTest {
    @Test
    void gzipFragmentsRoundTripAndRejectCorruption() {
        byte[] payload = new byte[40_000];
        new Random(42).nextBytes(payload);
        AssetSource source = new AssetSource("survival", "world", "minecraft:overworld");
        FederatedSnapshot.Bundle bundle = FederatedSnapshotCodec.encode("snapshot-1", FederatedSnapshot.Kind.MAP,
                source, 7, "application/x-kseco-map-v1", payload, List.of(), 1_000, 10_000, 4_096);

        assertTrue(bundle.fragments().size() > 1);
        assertArrayEquals(payload, FederatedSnapshotCodec.decode(bundle));

        List<FederatedSnapshot.Fragment> fragments = new java.util.ArrayList<>(bundle.fragments());
        byte[] changed = fragments.getFirst().bytes();
        changed[changed.length / 2] ^= 1;
        fragments.set(0, new FederatedSnapshot.Fragment(0, changed));
        FederatedSnapshot.Bundle corrupt = new FederatedSnapshot.Bundle(bundle.metadata(), fragments, List.of());
        assertThrows(IllegalArgumentException.class, () -> FederatedSnapshotCodec.decode(corrupt));
    }
}
