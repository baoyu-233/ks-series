package org.kseries.instanceworld.internal;

import org.bukkit.World;
import org.kseries.instanceworld.api.InstanceBounds;

import java.nio.file.Path;

/** Loads the WorldEdit adapter only after its complete API is known to be present. */
public final class OptionalSchematicRuntime {
    private static final String ADAPTER = "org.kseries.instanceworld.internal.WorldEditSchematicRuntime";

    private OptionalSchematicRuntime() { }

    public static SchematicRuntime load(ClassLoader loader) {
        try {
            Class.forName("com.sk89q.worldedit.world.block.BlockStateHolder", false, loader);
            Class.forName("com.sk89q.worldedit.WorldEdit", false, loader);
            Object adapter = Class.forName(ADAPTER, true, loader).getDeclaredConstructor().newInstance();
            return (SchematicRuntime) adapter;
        } catch (ClassNotFoundException | LinkageError missingDependency) {
            return UnavailableRuntime.INSTANCE;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not initialize optional WorldEdit adapter", failure);
        }
    }

    private enum UnavailableRuntime implements SchematicRuntime {
        INSTANCE;

        @Override public boolean available() { return false; }
        @Override public void register(String namespace, Path root) { }
        @Override public void unregister(String namespace) { }
        @Override public LoadedSchematic load(String namespace, String name) {
            throw new IllegalStateException("FastAsyncWorldEdit or WorldEdit is required for schematic preparation");
        }
        @Override public InstanceBounds clearAndPaste(World world, Object clipboard, int centerX,
                                                       int pasteY, int centerZ, int radius) {
            throw new IllegalStateException("FastAsyncWorldEdit or WorldEdit is required for schematic preparation");
        }
        @Override public void clear(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
            throw new IllegalStateException("FastAsyncWorldEdit or WorldEdit is required for schematic cleanup");
        }
    }
}
