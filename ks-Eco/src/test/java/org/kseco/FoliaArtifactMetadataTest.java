package org.kseco;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaArtifactMetadataTest {
    @Test
    void filteredPluginMetadataMatchesActiveProfile() throws Exception {
        String pluginYml = Files.readString(Path.of("target", "classes", "plugin.yml"));
        String expected = System.getProperty("kseco.folia.expected", "false");
        assertTrue(pluginYml.contains("folia-supported: " + expected), pluginYml);
    }
}
