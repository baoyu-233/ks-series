package org.kseco.crossserver.assets;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Parses a complete candidate before atomically replacing the active policy. */
public final class FederatedPolicyManager {
    private final AtomicReference<FederatedAccessPolicy> active =
            new AtomicReference<>(FederatedAccessPolicy.denyAll());
    private final AtomicLong generation = new AtomicLong();

    public FederatedAccessPolicy current() {
        return active.get();
    }

    public long generation() {
        return generation.get();
    }

    public long reload(Map<String, ?> candidate) {
        FederatedAccessPolicy parsed = FederatedAccessPolicy.fromMap(Objects.requireNonNull(candidate, "candidate"));
        active.set(parsed);
        return generation.incrementAndGet();
    }
}
