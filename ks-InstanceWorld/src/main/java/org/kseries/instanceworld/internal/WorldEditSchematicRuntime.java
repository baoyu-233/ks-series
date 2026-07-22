package org.kseries.instanceworld.internal;

import org.bukkit.World;
import org.kseries.instanceworld.api.InstanceBounds;

import java.nio.file.Path;

/** Hard-linked WorldEdit implementation; instantiated reflectively only when WorldEdit is present. */
public final class WorldEditSchematicRuntime implements SchematicRuntime {
    private final SchematicRepository repository = new SchematicRepository();
    private final CanvasService canvas = new CanvasService();

    public WorldEditSchematicRuntime() { }

    @Override public boolean available() { return canvas.available(); }
    @Override public void register(String namespace, Path root) { repository.register(namespace, root); }
    @Override public void unregister(String namespace) { repository.unregister(namespace); }

    @Override
    public LoadedSchematic load(String namespace, String name) throws Exception {
        SchematicRepository.LoadedSchematic loaded = repository.load(namespace, name);
        return new LoadedSchematic(loaded.source(), loaded.clipboard());
    }

    @Override
    public InstanceBounds clearAndPaste(World world, Object clipboard, int centerX, int pasteY,
                                        int centerZ, int radius) throws Exception {
        return canvas.clearAndPaste(world,
                (com.sk89q.worldedit.extent.clipboard.Clipboard) clipboard,
                centerX, pasteY, centerZ, radius);
    }

    @Override
    public void clear(World world, int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        canvas.clear(world, x1, y1, z1, x2, y2, z2);
    }
}
