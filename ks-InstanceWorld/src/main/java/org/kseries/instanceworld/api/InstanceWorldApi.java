package org.kseries.instanceworld.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Stable instance-world API obtained from Bukkit's ServicesManager.
 * Registration, prepare and release calls start on the server thread. Returned
 * futures are completed on the server thread so consumers can safely perform
 * Bukkit work in completion handlers.
 */
public interface InstanceWorldApi {
    void registerSchematicRoot(String namespace, Path root);

    void unregisterSchematicRoot(String namespace);

    InstancePreparation prepare(InstancePrepareRequest request);

    /**
     * Reattach a persisted READY instance after an unclean server stop. The call
     * starts on the server thread and completes on the server thread after the
     * world and marker snapshot have been rebuilt.
     */
    CompletableFuture<PreparedInstance> resume(String instanceId);

    CompletableFuture<ReleaseResult> release(String instanceId, ReleaseCause cause);

    Optional<InstanceSnapshot> instance(String instanceId);

    List<GridSnapshot> grids();

    int freeGridCount();

    int maxGridCount(String worldName);
}
