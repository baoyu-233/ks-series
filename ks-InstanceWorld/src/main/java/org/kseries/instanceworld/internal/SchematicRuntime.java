package org.kseries.instanceworld.internal;

import org.bukkit.World;
import org.kseries.instanceworld.api.InstanceBounds;

import java.nio.file.Path;

/** Neutral boundary that keeps optional WorldEdit classes out of the plugin lifecycle. */
public interface SchematicRuntime {
    boolean available();
    void register(String namespace, Path root);
    void unregister(String namespace);
    LoadedSchematic load(String namespace, String name) throws Exception;
    InstanceBounds clearAndPaste(World world, Object clipboard, int centerX, int pasteY,
                                 int centerZ, int radius) throws Exception;
    void clear(World world, int x1, int y1, int z1, int x2, int y2, int z2) throws Exception;

    record LoadedSchematic(Path source, Object clipboard) { }
}
