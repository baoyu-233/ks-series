package org.kseries.instanceworld.internal;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.kseries.instanceworld.api.InstanceBounds;
import org.kseries.instanceworld.api.InstanceMarker;
import org.kseries.instanceworld.api.InstancePoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MarkerScanner {
    private final Plugin plugin;
    private final int blocksPerTick;

    public MarkerScanner(Plugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = Math.max(1000, blocksPerTick);
    }

    public CompletableFuture<List<InstanceMarker>> scan(World world, InstanceBounds bounds, BooleanSupplier cancelled) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Marker scans must start on the server thread");
        CompletableFuture<List<InstanceMarker>> result = new CompletableFuture<>();
        Cursor cursor = new Cursor(world, bounds, cancelled, result);
        cursor.task = Bukkit.getScheduler().runTaskTimer(plugin, cursor, 1L, 1L);
        return result;
    }

    private final class Cursor implements Runnable {
        private final World world;
        private final InstanceBounds box;
        private final BooleanSupplier cancelled;
        private final CompletableFuture<List<InstanceMarker>> result;
        private final List<InstanceMarker> markers = new ArrayList<>();
        private int x;
        private int y;
        private int z;
        private BukkitTask task;

        private Cursor(World world, InstanceBounds box, BooleanSupplier cancelled,
                       CompletableFuture<List<InstanceMarker>> result) {
            this.world = world;
            this.box = box;
            this.cancelled = cancelled;
            this.result = result;
            this.x = box.minX();
            this.y = box.minY();
            this.z = box.minZ();
        }

        @Override
        public void run() {
            if (cancelled.getAsBoolean()) {
                finishExceptionally(new IllegalStateException("Instance preparation cancelled"));
                return;
            }
            try {
                for (int processed = 0; processed < blocksPerTick; processed++) {
                    inspect();
                    if (!advance()) {
                        task.cancel();
                        result.complete(List.copyOf(markers));
                        return;
                    }
                }
            } catch (Throwable failure) {
                finishExceptionally(failure);
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

        private void finishExceptionally(Throwable failure) {
            if (task != null) task.cancel();
            result.completeExceptionally(failure);
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
