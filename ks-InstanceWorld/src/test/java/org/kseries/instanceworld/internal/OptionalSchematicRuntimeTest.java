package org.kseries.instanceworld.internal;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OptionalSchematicRuntimeTest {
    @Test
    void missingWorldEditLeavesLifecycleRuntimeLoadable() {
        ClassLoader withoutWorldEdit = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.sk89q.worldedit.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        SchematicRuntime runtime = assertDoesNotThrow(() -> OptionalSchematicRuntime.load(withoutWorldEdit));
        assertFalse(runtime.available());
        assertDoesNotThrow(() -> runtime.register("test", Path.of("schematics")));
        assertThrows(IllegalStateException.class, () -> runtime.load("test", "arena.schem"));
    }

    @Test
    void lifecycleClassConstantPoolDoesNotReferenceWorldEdit() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "org/kseries/instanceworld/InstanceWorldService.class")) {
            assertNotNull(input);
            String constantPool = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constantPool.contains("com/sk89q/worldedit"));
            assertFalse(constantPool.contains("com.sk89q.worldedit"));
        }
    }
}
