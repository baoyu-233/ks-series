package org.kseries.instanceworld.internal;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.kseries.instanceworld.api.InstanceBounds;

public final class CanvasService {
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")
                || Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
    }

    public InstanceBounds clearAndPaste(World world, Clipboard clipboard, int centerX, int pasteY,
                                        int centerZ, int radius) throws Exception {
        requireServerThread();
        BlockVector3 destination = BlockVector3.at(centerX, pasteY, centerZ);
        Region region = clipboard.getRegion();
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 pastedMin = destination.add(region.getMinimumPoint().subtract(origin));
        BlockVector3 pastedMax = destination.add(region.getMaximumPoint().subtract(origin));
        int clearMinY = Math.max(world.getMinHeight(), Math.min(pasteY - 16, Math.min(pastedMin.y(), pastedMax.y())));
        int clearMaxY = Math.min(world.getMaxHeight() - 1, Math.max(pasteY + 255, Math.max(pastedMin.y(), pastedMax.y())));
        int clearMinX = Math.min(centerX - radius, Math.min(pastedMin.x(), pastedMax.x()));
        int clearMaxX = Math.max(centerX + radius, Math.max(pastedMin.x(), pastedMax.x()));
        int clearMinZ = Math.min(centerZ - radius, Math.min(pastedMin.z(), pastedMax.z()));
        int clearMaxZ = Math.max(centerZ + radius, Math.max(pastedMin.z(), pastedMax.z()));
        clear(world, clearMinX, clearMinY, clearMinZ, clearMaxX, clearMaxY, clearMaxZ);
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
            Operation operation = new ClipboardHolder(clipboard).createPaste(edit)
                    .to(destination).ignoreAirBlocks(false).build();
            Operations.complete(operation);
        }
        return new InstanceBounds(Math.min(pastedMin.x(), pastedMax.x()), Math.min(pastedMin.y(), pastedMax.y()),
                Math.min(pastedMin.z(), pastedMax.z()),
                Math.max(pastedMin.x(), pastedMax.x()), Math.max(pastedMin.y(), pastedMax.y()),
                Math.max(pastedMin.z(), pastedMax.z()));
    }

    public void clear(World world, int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        requireServerThread();
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        BlockVector3 min = BlockVector3.at(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
        BlockVector3 max = BlockVector3.at(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
            edit.setBlocks(new CuboidRegion(weWorld, min, max), BlockTypes.AIR.getDefaultState());
        }
    }

    private static void requireServerThread() {
        if (!Bukkit.isGlobalTickThread() && !foliaRuntime()) {
            throw new IllegalStateException("WorldEdit interaction must run on the server thread");
        }
    }

    private static boolean foliaRuntime() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false,
                    CanvasService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
