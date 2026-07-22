package org.kseries.rpg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KsRpgBundledContentTest {
    @Test
    void discoversEveryYamlWaveInKnownCombatCategories() {
        List<String> resources = KsRpg.bundledCombatResources(Stream.of(
                "plugin.yml",
                "content/weapons/second-wave.yml",
                "content/weapons/first-wave.yml",
                "content/talismans/season-two/third-wave.YML",
                "content/world-drops/second-wave.yml",
                "content/unknown/ignored.yml",
                "content/rings/readme.txt"));

        assertEquals(List.of(
                "content/talismans/season-two/third-wave.YML",
                "content/weapons/first-wave.yml",
                "content/weapons/second-wave.yml",
                "content/world-drops/second-wave.yml"), resources);
    }

    @Test
    void rejectsMalformedOrTraversalResourceNames() {
        List<String> resources = KsRpg.bundledCombatResources(Stream.of(
                "content/weapons/../secret.yml",
                "content//empty.yml",
                "content\\weapons\\windows.yml",
                "content/weapons/valid.yml"));

        assertEquals(List.of("content/weapons/valid.yml"), resources);
    }
}
