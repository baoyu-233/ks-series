package org.kseries.instanceworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.kseries.instanceworld.api.GridSnapshot;
import org.kseries.instanceworld.api.InstanceBounds;
import org.kseries.instanceworld.api.InstanceGridSpec;
import org.kseries.instanceworld.api.InstanceLifecycleEvent;
import org.kseries.instanceworld.api.InstanceMarker;
import org.kseries.instanceworld.api.InstancePoint;
import org.kseries.instanceworld.api.InstancePreparation;
import org.kseries.instanceworld.api.InstancePrepareRequest;
import org.kseries.instanceworld.api.InstanceSnapshot;
import org.kseries.instanceworld.api.InstanceState;
import org.kseries.instanceworld.api.InstanceWorldApi;
import org.kseries.instanceworld.api.PreparedInstance;
import org.kseries.instanceworld.api.ReleaseCause;
import org.kseries.instanceworld.api.ReleaseResult;
import org.kseries.instanceworld.internal.CanvasService;
import org.kseries.instanceworld.internal.InstanceStore;
import org.kseries.instanceworld.internal.MarkerScanner;
import org.kseries.instanceworld.internal.SchematicRepository;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

final class InstanceWorldService implements InstanceWorldApi {
    private final KsInstanceWorld plugin;
    private final ExecutorService workers;
    private final InstanceStore store;
    private final SchematicRepository schematics = new SchematicRepository();
    private final CanvasService canvas = new CanvasService();
    private final MarkerScanner markerScanner;
    private final Path serverRoot;
    private final int defaultMaxGrids;
    private final String sessionId = UUID.randomUUID().toString();
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final Map<String, InstanceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, InstanceStore.RecoverableInstance> recoverableInstances = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ReleaseResult>> releases = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> poolCaps = new ConcurrentHashMap<>();
    private volatile List<GridSnapshot> gridCache = List.of();
    private volatile boolean shuttingDown;

    InstanceWorldService(KsInstanceWorld plugin, ExecutorService workers) {
        this.plugin = plugin;
        this.workers = workers;
        Path database = plugin.getDataFolder().toPath().resolve(
                plugin.getConfig().getString("database.file", "instance-world.db"));
        this.store = new InstanceStore(database, plugin.getLogger());
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.defaultMaxGrids = plugin.getConfig().getInt("defaults.max-grids", 64);
        this.markerScanner = new MarkerScanner(plugin,
                plugin.getConfig().getInt("markers.blocks-per-tick", 50_000));
    }

    void initialize() {
        CompletableFuture.runAsync(() -> {
            try {
                store.initialize();
                importLegacyIfEnabled();
                int recovered = store.recoverInterruptedInstances();
                gridCache = List.copyOf(store.loadGrids());
                store.loadInstances().forEach(snapshot -> snapshots.put(snapshot.instanceId(), snapshot));
                store.loadRecoverableInstances().forEach(instance ->
                        recoverableInstances.put(instance.snapshot().instanceId(), instance));
                poolCaps.putAll(store.loadPoolCaps());
                if (recovered > 0) {
                    plugin.getLogger().warning("Recovered " + recovered + " interrupted instance-world records.");
                }
                initialized.complete(null);
            } catch (Throwable failure) {
                initialized.completeExceptionally(failure);
                plugin.getLogger().severe("Failed to initialize instance-world storage: " + failure.getMessage());
            }
        }, workers);
    }

    private void importLegacyIfEnabled() throws Exception {
        if (!plugin.getConfig().getBoolean("legacy-grid-import.enabled", true)) return;
        String configured = plugin.getConfig().getString("legacy-grid-import.sqlite-path", "plugins/ks-core/data.db");
        String table = plugin.getConfig().getString("legacy-grid-import.table", "ks_dungeon_grids");
        Path source = serverRoot.resolve(configured).normalize();
        int imported = store.importLegacyGrids(source, table);
        if (imported > 0) {
            plugin.getLogger().info("Imported " + imported + " legacy grid coordinates without modifying the source database.");
        }
    }

    @Override
    public void registerSchematicRoot(String namespace, Path root) {
        requireServerThread();
        schematics.register(namespace, root);
    }

    @Override
    public void unregisterSchematicRoot(String namespace) {
        requireServerThread();
        schematics.unregister(namespace);
    }

    @Override
    public InstancePreparation prepare(InstancePrepareRequest request) {
        requireServerThread();
        String instanceId = "IWI-" + UUID.randomUUID();
        CompletableFuture<PreparedInstance> result = new CompletableFuture<>();
        if (shuttingDown) {
            result.completeExceptionally(new IllegalStateException("ks-InstanceWorld is shutting down"));
            return new InstancePreparation(instanceId, result);
        }
        if (!canvas.available()) {
            result.completeExceptionally(new IllegalStateException("FastAsyncWorldEdit or WorldEdit is required"));
            return new InstancePreparation(instanceId, result);
        }
        poolCaps.put(request.grid().worldName(), request.grid().maxGrids());
        Context context = new Context(instanceId, request, result);
        contexts.put(instanceId, context);
        initialized.whenComplete((ignored, initFailure) -> {
            if (initFailure != null) {
                context.leaseReady.completeExceptionally(unwrap(initFailure));
                runMain(() -> {
                    result.completeExceptionally(unwrap(initFailure));
                    contexts.remove(instanceId, context);
                });
                return;
            }
            if (shuttingDown || context.cancelled.get()) {
                context.leaseReady.completeExceptionally(new CancellationException("Instance request cancelled before allocation"));
                runMain(() -> {
                    result.completeExceptionally(new CancellationException("Instance request cancelled before allocation"));
                    contexts.remove(instanceId, context);
                });
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (context.cancelled.get()) throw new CancellationException("Instance request cancelled before allocation");
                    return store.allocate(instanceId, sessionId, request);
                } catch (Throwable failure) {
                    throw new CompletionException(failure);
                }
            }, workers).whenComplete((lease, allocationFailure) -> {
                if (shuttingDown) {
                    if (allocationFailure != null) context.leaseReady.completeExceptionally(unwrap(allocationFailure));
                    else {
                        context.lease = lease;
                        context.leaseReady.complete(lease);
                    }
                    return;
                }
                runMain(() -> {
                if (allocationFailure != null) {
                    context.leaseReady.completeExceptionally(unwrap(allocationFailure));
                    result.completeExceptionally(unwrap(allocationFailure));
                    contexts.remove(instanceId, context);
                    return;
                }
                context.lease = lease;
                context.leaseReady.complete(lease);
                if (context.cancelled.get()) return;
                updateSnapshot(context, InstanceState.PREPARING, null, null, null, "");
                refreshGridsAsync();
                long timeoutTicks = Math.max(1L, request.timeout().toMillis() / 50L);
                timeoutTasks.put(context.instanceId, Bukkit.getScheduler().runTaskLater(plugin,
                        () -> failPreparation(context, new IllegalStateException("Instance preparation timed out")), timeoutTicks));
                loadSchematic(context);
                });
            });
        });
        return new InstancePreparation(instanceId, result);
    }

    @Override
    public CompletableFuture<PreparedInstance> resume(String instanceId) {
        requireServerThread();
        CompletableFuture<PreparedInstance> result = new CompletableFuture<>();
        if (instanceId == null || instanceId.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("instanceId is required"));
            return result;
        }
        if (shuttingDown) {
            result.completeExceptionally(new IllegalStateException("ks-InstanceWorld is shutting down"));
            return result;
        }
        Context active = contexts.get(instanceId);
        if (active != null && active.result.isDone() && !active.result.isCompletedExceptionally()) {
            active.result.whenComplete((prepared, failure) -> {
                if (failure == null) result.complete(prepared);
                else result.completeExceptionally(unwrap(failure));
            });
            return result;
        }
        initialized.whenComplete((ignored, initFailure) -> runMain(() -> {
            if (initFailure != null) {
                result.completeExceptionally(unwrap(initFailure));
                return;
            }
            InstanceStore.RecoverableInstance recovery = recoverableInstances.get(instanceId);
            if (recovery == null || recovery.snapshot().state() != InstanceState.READY) {
                result.completeExceptionally(new IllegalStateException("Instance is not recoverable: " + instanceId));
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    return store.claimReady(instanceId, sessionId);
                } catch (Throwable failure) {
                    throw new CompletionException(failure);
                }
            }, workers).whenComplete((claimed, claimFailure) -> runMain(() -> {
                if (claimFailure != null || !Boolean.TRUE.equals(claimed)) {
                    result.completeExceptionally(claimFailure == null
                            ? new IllegalStateException("Could not claim persisted instance " + instanceId)
                            : unwrap(claimFailure));
                    return;
                }
                resumeClaimed(recovery, result);
            }));
        }));
        return result;
    }

    private void resumeClaimed(InstanceStore.RecoverableInstance recovery,
                               CompletableFuture<PreparedInstance> result) {
        requireServerThread();
        InstanceSnapshot snapshot = recovery.snapshot();
        World world = loadWorld(snapshot.worldName());
        if (world == null) {
            result.completeExceptionally(new IllegalStateException(
                    "Could not load recovered instance world " + snapshot.worldName()));
            return;
        }
        Context context = recoveredContext(recovery, world);
        Context raced = contexts.putIfAbsent(snapshot.instanceId(), context);
        if (raced != null) {
            raced.result.whenComplete((existing, failure) -> {
                if (failure == null) result.complete(existing);
                else result.completeExceptionally(unwrap(failure));
            });
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        markerScanner.scan(world, snapshot.bounds(), cancelled::get)
                .whenComplete((markers, scanFailure) -> runMain(() -> {
                    if (scanFailure != null) {
                        context.result.completeExceptionally(unwrap(scanFailure));
                        result.completeExceptionally(unwrap(scanFailure));
                        return;
                    }
                    context.markers = List.copyOf(markers == null ? List.of() : markers);
                    PreparedInstance prepared = new PreparedInstance(snapshot.instanceId(), snapshot.namespace(),
                            snapshot.templateKey(), world, snapshot.gridId(), snapshot.gridCenterX(),
                            snapshot.gridCenterZ(), snapshot.bounds(), snapshot.pasteCenter(), snapshot.spawn(),
                            context.markers);
                    context.result.complete(prepared);
                    updateSnapshot(context, InstanceState.READY, context.bounds,
                            context.pasteCenter, context.spawn, "");
                    refreshGridsAsync();
                    result.complete(prepared);
                }));
    }

    private Context recoveredContext(InstanceStore.RecoverableInstance recovery, World world) {
        InstanceSnapshot snapshot = recovery.snapshot();
        InstancePrepareRequest request = new InstancePrepareRequest(
                snapshot.namespace(), snapshot.templateKey(), recovery.schematicName(),
                new InstanceGridSpec(snapshot.worldName(), 128, 1),
                snapshot.pasteCenter() == null ? snapshot.bounds().minY()
                        : (int) Math.floor(snapshot.pasteCenter().y()),
                recovery.arenaRadius(), Duration.ofSeconds(30));
        Context context = new Context(snapshot.instanceId(), request, new CompletableFuture<>());
        context.lease = new InstanceStore.GridLease(snapshot.gridId(), snapshot.worldName(),
                snapshot.gridCenterX(), snapshot.gridCenterZ());
        context.leaseReady.complete(context.lease);
        context.bounds = snapshot.bounds();
        context.pasteCenter = snapshot.pasteCenter();
        context.spawn = snapshot.spawn();
        context.prepareSettled.set(true);
        return context;
    }

    private void loadSchematic(Context context) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return schematics.load(context.request.namespace(), context.request.schematicName());
            } catch (Throwable failure) {
                throw new CompletionException(failure);
            }
        }, workers).whenComplete((loaded, failure) -> runMain(() -> {
            if (failure != null) {
                failPreparation(context, unwrap(failure));
                return;
            }
            if (context.cancelled.get()) return;
            try {
                World world = loadWorld(context.lease.worldName());
                if (world == null) throw new IllegalStateException("Could not load instance world " + context.lease.worldName());
                context.bounds = canvas.clearAndPaste(world, loaded.clipboard(), context.lease.centerX(),
                        context.request.pasteY(), context.lease.centerZ(), context.request.arenaRadius());
                context.pasteCenter = resolvePasteCenter(world, context.bounds);
                markerScanner.scan(world, context.bounds, context.cancelled::get)
                        .whenComplete((markers, scanFailure) -> runMain(() -> {
                            if (scanFailure != null) failPreparation(context, unwrap(scanFailure));
                            else persistReady(context, markers);
                        }));
            } catch (Throwable preparationFailure) {
                failPreparation(context, preparationFailure);
            }
        }));
    }

    private void persistReady(Context context, List<InstanceMarker> markers) {
        if (context.cancelled.get() || context.prepareSettled.get()) return;
        InstancePoint spawn = markers.stream()
                .filter(marker -> "[spawn]".equalsIgnoreCase(marker.tag()))
                .map(InstanceMarker::point)
                .findFirst()
                .orElse(context.pasteCenter);
        context.spawn = spawn;
        context.markers = List.copyOf(markers);
        String instanceId = context.instanceId;
        InstanceBounds bounds = context.bounds;
        InstancePoint pasteCenter = context.pasteCenter;
        InstancePoint spawnPoint = context.spawn;
        CompletableFuture.runAsync(() -> {
            try {
                store.markReady(instanceId, bounds, pasteCenter, spawnPoint);
            } catch (Throwable failure) {
                throw new CompletionException(failure);
            }
        }, workers).whenComplete((ignored, failure) -> runMain(() -> {
            if (failure != null) {
                failPreparation(context, unwrap(failure));
                return;
            }
            World preparedWorld = Bukkit.getWorld(context.lease.worldName());
            if (preparedWorld == null) {
                failPreparation(context, new IllegalStateException("Instance world unloaded before activation"));
                return;
            }
            if (!context.prepareSettled.compareAndSet(false, true)) return;
            cancelTimeout(context);
            updateSnapshot(context, InstanceState.READY, context.bounds, context.pasteCenter, context.spawn, "");
            PreparedInstance prepared = new PreparedInstance(context.instanceId, context.request.namespace(),
                    context.request.templateKey(), preparedWorld, context.lease.gridId(), context.lease.centerX(),
                    context.lease.centerZ(), context.bounds, context.pasteCenter, context.spawn, context.markers);
            context.result.complete(prepared);
            fire(InstanceLifecycleEvent.Phase.PREPARED, snapshots.get(context.instanceId), null);
            refreshGridsAsync();
        }));
    }

    private void failPreparation(Context context, Throwable failure) {
        requireServerThread();
        if (!context.prepareSettled.compareAndSet(false, true)) return;
        context.cancelled.set(true);
        cancelTimeout(context);
        String message = failure == null ? "Instance preparation failed" : safeMessage(failure);
        try {
            cleanup(context);
        } catch (Throwable cleanupFailure) {
            message += "; cleanup: " + safeMessage(cleanupFailure);
        }
        String finalMessage = message;
        String instanceId = context.instanceId;
        CompletableFuture.runAsync(() -> {
            try {
                store.finishRelease(instanceId, true, finalMessage);
            } catch (Throwable storageFailure) {
                throw new CompletionException(storageFailure);
            }
        }, workers).whenComplete((ignored, storageFailure) -> runMain(() -> {
            String persistedError = storageFailure == null ? finalMessage : finalMessage + "; storage: " + safeMessage(unwrap(storageFailure));
            updateSnapshot(context, InstanceState.FAILED, context.bounds, context.pasteCenter, context.spawn, persistedError);
            fire(InstanceLifecycleEvent.Phase.PREPARATION_FAILED, snapshots.get(context.instanceId), ReleaseCause.PREPARATION_FAILED);
            context.result.completeExceptionally(failure == null ? new IllegalStateException(finalMessage) : failure);
            contexts.remove(context.instanceId, context);
            refreshGridsAsync();
        }));
    }

    @Override
    public CompletableFuture<ReleaseResult> release(String instanceId, ReleaseCause cause) {
        requireServerThread();
        if (instanceId == null || instanceId.isBlank()) return failedFuture(new IllegalArgumentException("instanceId is required"));
        ReleaseCause effectiveCause = cause == null ? ReleaseCause.EXTERNAL : cause;
        InstanceSnapshot snapshot = snapshots.get(instanceId);
        if (snapshot != null && (snapshot.state() == InstanceState.RELEASED || snapshot.state() == InstanceState.FAILED)) {
            return CompletableFuture.completedFuture(new ReleaseResult(instanceId, false, true, "Instance already released"));
        }
        CompletableFuture<ReleaseResult> result = releases.computeIfAbsent(instanceId,
                ignored -> startRelease(instanceId, effectiveCause));
        result.whenComplete((ignored, failure) -> releases.remove(instanceId, result));
        return result;
    }

    private CompletableFuture<ReleaseResult> startRelease(String instanceId, ReleaseCause cause) {
        CompletableFuture<ReleaseResult> result = new CompletableFuture<>();
        Context context = contexts.get(instanceId);
        if (context == null) {
            InstanceStore.RecoverableInstance recovery = recoverableInstances.get(instanceId);
            if (recovery == null) {
                result.complete(new ReleaseResult(instanceId, false, true, "Instance is not active in this server session"));
                return result;
            }
            World world = loadWorld(recovery.snapshot().worldName());
            if (world == null) {
                result.completeExceptionally(new IllegalStateException(
                        "Could not load persisted instance world " + recovery.snapshot().worldName()));
                return result;
            }
            context = recoveredContext(recovery, world);
            contexts.put(instanceId, context);
        }
        Context activeContext = context;
        activeContext.cancelled.set(true);
        if (activeContext.prepareSettled.compareAndSet(false, true)) {
            cancelTimeout(activeContext);
            activeContext.result.completeExceptionally(new CancellationException("Instance released during preparation"));
        }
        activeContext.leaseReady.whenComplete((lease, leaseFailure) -> runMain(() -> {
            if (leaseFailure != null) {
                contexts.remove(instanceId, activeContext);
                result.complete(new ReleaseResult(instanceId, false, true, "Instance allocation did not complete"));
                return;
            }
            continueRelease(activeContext, cause, result);
        }));
        return result;
    }

    private void continueRelease(Context context, ReleaseCause cause, CompletableFuture<ReleaseResult> result) {
        String instanceId = context.instanceId;
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.markReleasing(instanceId);
            } catch (Throwable failure) {
                throw new CompletionException(failure);
            }
        }, workers).whenComplete((changed, markFailure) -> runMain(() -> {
            if (markFailure != null) {
                result.completeExceptionally(unwrap(markFailure));
                releases.remove(instanceId, result);
                return;
            }
            updateSnapshot(context, InstanceState.RELEASING, context.bounds, context.pasteCenter, context.spawn, "");
            fire(InstanceLifecycleEvent.Phase.RELEASE_STARTED, snapshots.get(instanceId), cause);
            String cleanupError = "";
            try {
                cleanup(context);
            } catch (Throwable cleanupFailure) {
                cleanupError = safeMessage(cleanupFailure);
            }
            String finalCleanupError = cleanupError;
            CompletableFuture.runAsync(() -> {
                try {
                    store.finishRelease(instanceId, false, finalCleanupError);
                } catch (Throwable failure) {
                    throw new CompletionException(failure);
                }
            }, workers).whenComplete((ignored, finishFailure) -> runMain(() -> {
                if (finishFailure != null) {
                    result.completeExceptionally(unwrap(finishFailure));
                    releases.remove(instanceId, result);
                    return;
                }
                updateSnapshot(context, InstanceState.RELEASED, context.bounds, context.pasteCenter,
                        context.spawn, finalCleanupError);
                recoverableInstances.remove(instanceId);
                fire(InstanceLifecycleEvent.Phase.RELEASE_COMPLETED, snapshots.get(instanceId), cause);
                contexts.remove(instanceId, context);
                result.complete(new ReleaseResult(instanceId, true, false,
                        finalCleanupError.isBlank() ? "Released" : "Released with cleanup warning: " + finalCleanupError));
                refreshGridsAsync();
            }));
        }));
    }

    private World loadWorld(String worldName) {
        requireServerThread();
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) return loaded;
        WorldCreator creator = new WorldCreator(worldName)
                .type(WorldType.FLAT)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}")
                .generateStructures(false);
        return Bukkit.createWorld(creator);
    }

    private InstancePoint resolvePasteCenter(World world, InstanceBounds bounds) {
        int x = (bounds.minX() + bounds.maxX()) / 2;
        int z = (bounds.minZ() + bounds.maxZ()) / 2;
        int highest = world.getHighestBlockYAt(x, z);
        int y = highest <= world.getMinHeight() ? bounds.minY() : highest + 1;
        return new InstancePoint(x + 0.5, y, z + 0.5, 0, 0);
    }

    private void cleanup(Context context) throws Exception {
        requireServerThread();
        World world = Bukkit.getWorld(context.lease.worldName());
        if (world == null) return;
        InstanceBounds cleanupBounds = resolveCleanupBounds(context, world);
        double halfX = (cleanupBounds.maxX() - cleanupBounds.minX()) / 2.0 + 1.0;
        double halfY = (cleanupBounds.maxY() - cleanupBounds.minY()) / 2.0 + 1.0;
        double halfZ = (cleanupBounds.maxZ() - cleanupBounds.minZ()) / 2.0 + 1.0;
        Location center = new Location(world,
                (cleanupBounds.minX() + cleanupBounds.maxX()) / 2.0 + 0.5,
                (cleanupBounds.minY() + cleanupBounds.maxY()) / 2.0 + 0.5,
                (cleanupBounds.minZ() + cleanupBounds.maxZ()) / 2.0 + 0.5);
        Exception cleanupFailure = null;
        try {
            for (Entity entity : world.getNearbyEntities(center, halfX, halfY, halfZ)) {
                if (!(entity instanceof Player)) entity.remove();
            }
        } catch (Exception failure) {
            cleanupFailure = failure;
        }
        if (canvas.available()) {
            try {
                canvas.clear(world, cleanupBounds.minX(), cleanupBounds.minY(), cleanupBounds.minZ(),
                        cleanupBounds.maxX(), cleanupBounds.maxY(), cleanupBounds.maxZ());
            } catch (Exception failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }

    private InstanceBounds resolveCleanupBounds(Context context, World world) {
        int radius = Math.max(0, context.request.arenaRadius());
        int clearMinY = Math.max(world.getMinHeight(), context.request.pasteY() - 16);
        int clearMaxY = Math.min(world.getMaxHeight() - 1, context.request.pasteY() + 255);
        InstanceBounds arena = new InstanceBounds(
                context.lease.centerX() - radius, clearMinY, context.lease.centerZ() - radius,
                context.lease.centerX() + radius, clearMaxY, context.lease.centerZ() + radius);
        if (context.bounds == null) return arena;
        return new InstanceBounds(
                Math.min(arena.minX(), context.bounds.minX()),
                Math.min(arena.minY(), context.bounds.minY()),
                Math.min(arena.minZ(), context.bounds.minZ()),
                Math.max(arena.maxX(), context.bounds.maxX()),
                Math.max(arena.maxY(), context.bounds.maxY()),
                Math.max(arena.maxZ(), context.bounds.maxZ()));
    }

    @Override
    public Optional<InstanceSnapshot> instance(String instanceId) {
        return Optional.ofNullable(snapshots.get(instanceId));
    }

    @Override
    public List<GridSnapshot> grids() { return gridCache; }

    @Override
    public int freeGridCount() {
        return (int) gridCache.stream().filter(grid -> "FREE".equals(grid.status())).count();
    }

    @Override
    public int maxGridCount(String worldName) {
        return poolCaps.getOrDefault(worldName, defaultMaxGrids);
    }

    void shutdown() {
        requireServerThread();
        shuttingDown = true;
        for (Context context : List.copyOf(contexts.values())) {
            context.cancelled.set(true);
            cancelTimeout(context);
            if (context.lease != null) {
                try {
                    cleanup(context);
                } catch (Throwable failure) {
                    plugin.getLogger().warning("Instance cleanup during disable failed for " + context.instanceId + ": " + safeMessage(failure));
                }
            }
            context.result.completeExceptionally(new CancellationException("ks-InstanceWorld disabled"));
            String instanceId = context.instanceId;
            Runnable releaseLateAllocation = () -> {
                try {
                    store.finishRelease(instanceId, true, "Plugin disabled during allocation");
                } catch (Exception failure) {
                    plugin.getLogger().warning("Late allocation release failed for " + instanceId + ": " + safeMessage(failure));
                }
            };
            if (context.leaseReady.isDone()) {
                CompletableFuture.runAsync(releaseLateAllocation, workers);
            } else {
                context.leaseReady.thenAccept(lease -> releaseLateAllocation.run());
            }
        }
        contexts.clear();
        recoverableInstances.clear();
        CompletableFuture.runAsync(() -> store.releaseSession(sessionId), workers);
    }

    private void refreshGridsAsync() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadGrids();
            } catch (Throwable failure) {
                throw new CompletionException(failure);
            }
        }, workers).thenAccept(grids -> gridCache = List.copyOf(grids))
                .exceptionally(failure -> {
                    plugin.getLogger().warning("Failed to refresh grid cache: " + safeMessage(unwrap(failure)));
                    return null;
                });
    }

    private void updateSnapshot(Context context, InstanceState state, InstanceBounds bounds,
                                InstancePoint pasteCenter, InstancePoint spawn, String error) {
        snapshots.put(context.instanceId, new InstanceSnapshot(context.instanceId, context.request.namespace(),
                context.request.templateKey(), context.lease.worldName(), context.lease.gridId(),
                context.lease.centerX(), context.lease.centerZ(), state, bounds, pasteCenter, spawn,
                error == null ? "" : error));
    }

    private void fire(InstanceLifecycleEvent.Phase phase, InstanceSnapshot snapshot, ReleaseCause cause) {
        requireServerThread();
        Bukkit.getPluginManager().callEvent(new InstanceLifecycleEvent(phase, snapshot, cause));
    }

    private void cancelTimeout(Context context) {
        BukkitTask task = timeoutTasks.remove(context.instanceId);
        if (task != null) task.cancel();
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else if (!shuttingDown && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return "unknown error";
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("InstanceWorldApi must be called on the server thread");
    }

    private static final class Context {
        private final String instanceId;
        private final InstancePrepareRequest request;
        private final CompletableFuture<PreparedInstance> result;
        private final CompletableFuture<InstanceStore.GridLease> leaseReady = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean prepareSettled = new AtomicBoolean();
        private volatile InstanceStore.GridLease lease;
        private InstanceBounds bounds;
        private InstancePoint pasteCenter;
        private InstancePoint spawn;
        private List<InstanceMarker> markers = List.of();

        private Context(String instanceId, InstancePrepareRequest request, CompletableFuture<PreparedInstance> result) {
            this.instanceId = instanceId;
            this.request = request;
            this.result = result;
        }
    }
}
