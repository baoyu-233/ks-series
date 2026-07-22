package org.kseries.instanceworld.internal;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.plugin.Plugin;
import org.kseries.instanceworld.api.InstanceBounds;
import org.kseries.instanceworld.api.InstanceMarker;
import org.kseries.instanceworld.api.InstancePoint;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Region-owned, chunk-partitioned marker scanner for Paper and Folia. */
public final class MarkerScanner {
    private final Plugin plugin;
    private final int blocksPerTick;

    public MarkerScanner(Plugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = Math.max(1000, blocksPerTick);
    }

    public CompletableFuture<List<InstanceMarker>> scan(World world, InstanceBounds bounds,
                                                         BooleanSupplier cancelled) {
        CompletableFuture<List<InstanceMarker>> result = new CompletableFuture<>();
        ConcurrentLinkedQueue<InstanceMarker> markers = new ConcurrentLinkedQueue<>();
        int chunkX1 = Math.floorDiv(bounds.minX(), 16), chunkX2 = Math.floorDiv(bounds.maxX(), 16);
        int chunkZ1 = Math.floorDiv(bounds.minZ(), 16), chunkZ2 = Math.floorDiv(bounds.maxZ(), 16);
        AtomicInteger remaining = new AtomicInteger((chunkX2 - chunkX1 + 1) * (chunkZ2 - chunkZ1 + 1));
        for (int cx = chunkX1; cx <= chunkX2; cx++) for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
            InstanceBounds slice = new InstanceBounds(
                    Math.max(bounds.minX(), cx << 4), bounds.minY(), Math.max(bounds.minZ(), cz << 4),
                    Math.min(bounds.maxX(), (cx << 4) + 15), bounds.maxY(), Math.min(bounds.maxZ(), (cz << 4) + 15));
            Cursor cursor = new Cursor(world, slice, cancelled, markers, remaining, result);
            Location owner = new Location(world, slice.minX(), slice.minY(), slice.minZ());
            cursor.task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, owner,
                    ignored -> cursor.run(), 1L, 1L);
        }
        return result;
    }

    private final class Cursor implements Runnable {
        private final World world;
        private final InstanceBounds box;
        private final BooleanSupplier cancelled;
        private final ConcurrentLinkedQueue<InstanceMarker> markers;
        private final AtomicInteger remaining;
        private final CompletableFuture<List<InstanceMarker>> result;
        private int x;
        private int y;
        private int z;
        private ScheduledTask task;

        private Cursor(World world, InstanceBounds box, BooleanSupplier cancelled,
                       ConcurrentLinkedQueue<InstanceMarker> markers, AtomicInteger remaining,
                       CompletableFuture<List<InstanceMarker>> result) {
            this.world = world;
            this.box = box;
            this.cancelled = cancelled;
            this.markers = markers;
            this.remaining = remaining;
            this.result = result;
            this.x = box.minX();
            this.y = box.minY();
            this.z = box.minZ();
        }

        @Override public void run() {
            if (result.isDone()) { task.cancel(); return; }
            if (cancelled.getAsBoolean()) {
                task.cancel();
                result.completeExceptionally(new IllegalStateException("Instance preparation cancelled"));
                return;
            }
            try {
                for (int processed = 0; processed < blocksPerTick; processed++) {
                    inspect();
                    if (!advance()) {
                        task.cancel();
                        if (remaining.decrementAndGet() == 0) result.complete(List.copyOf(markers));
                        return;
                    }
                }
            } catch (Throwable failure) {
                task.cancel();
                result.completeExceptionally(failure);
            }
        }

        private void inspect() {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().name().endsWith("_SIGN") || !(block.getState() instanceof Sign sign)) return;
            List<String> lines = Arrays.stream(sign.getLines()).map(MarkerScanner::clean).toList();
            String tag = lines.isEmpty() ? "" : lines.getFirst().toLowerCase(Locale.ROOT);
            if (tag.length() < 3 || !tag.startsWith("[") || !tag.endsWith("]")) return;
            markers.add(new InstanceMarker(tag, lines, new InstancePoint(x + 0.5, y, z + 0.5, 0, 0)));
            block.setType(Material.AIR, false);
        }

        private boolean advance() {
            if (++z <= box.maxZ()) return true;
            z = box.minZ();
            if (++y <= box.maxY()) return true;
            y = box.minY();
            return ++x <= box.maxX();
        }
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned.trim();
    }
}
