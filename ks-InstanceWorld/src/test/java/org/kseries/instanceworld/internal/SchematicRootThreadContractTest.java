package org.kseries.instanceworld.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchematicRootThreadContractTest {
    @Test
    void rootRegistrationAndRemovalDoNotRequireTheGlobalTickThread() throws Exception {
        SchematicRepository repository = new SchematicRepository();
        CompletableFuture.runAsync(() -> repository.register("dungeon", Path.of("schematics")))
                .get(5, TimeUnit.SECONDS);
        assertTrue(repository.registered("dungeon"));

        CompletableFuture.runAsync(() -> repository.unregister("dungeon"))
                .get(5, TimeUnit.SECONDS);
        assertFalse(repository.registered("dungeon"));
    }
}
