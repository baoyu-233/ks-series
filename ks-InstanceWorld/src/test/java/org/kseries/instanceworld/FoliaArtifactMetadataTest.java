package org.kseries.instanceworld;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaArtifactMetadataTest {
    @Test
    void artifactCarriesProfileSpecificCapability() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            String pluginYml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String expected = System.getProperty("ksinstanceworld.folia.expected", "false");
            assertTrue(pluginYml.contains("folia-supported: " + expected));
        }
    }
}
