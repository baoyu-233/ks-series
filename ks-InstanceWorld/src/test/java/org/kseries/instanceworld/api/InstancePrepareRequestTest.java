package org.kseries.instanceworld.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstancePrepareRequestTest {
    @Test
    void permitsContainedSubdirectoriesForPrivateContentPackages() {
        InstancePrepareRequest request = request("gaze/gaze.schem");

        assertEquals("gaze/gaze.schem", request.schematicName());
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        assertThrows(IllegalArgumentException.class, () -> request("../escape.schem"));
        assertThrows(IllegalArgumentException.class, () -> request("/escape.schem"));
        assertThrows(IllegalArgumentException.class, () -> request("gaze\\..\\escape.schem"));
    }

    private static InstancePrepareRequest request(String schematic) {
        return new InstancePrepareRequest("test", "template", schematic,
                new InstanceGridSpec("ks-dungeon-world", 5000, 1), 64, 96, Duration.ofSeconds(30));
    }
}
