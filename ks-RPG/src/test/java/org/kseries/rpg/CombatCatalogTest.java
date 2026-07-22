package org.kseries.rpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CombatCatalogTest {
    @TempDir
    Path contentDirectory;

    @Test
    void rejectsUnknownActiveMechanicDuringCatalogLoad() throws Exception {
        write("weapons/invalid.yml", """
                invalid_blade:
                  item:
                    type: SWORD
                    id: INVALID_BLADE
                  trigger: SNEAK_RIGHT_CLICK
                  mechanics:
                    TELEPORT_STRIKE:
                      range: 6
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CombatCatalog.load(contentDirectory));

        assertEquals("Unknown active mechanic in invalid_blade: TELEPORT_STRIKE", error.getMessage());
    }

    @Test
    void rejectsActiveMechanicPlacedInPassiveContent() throws Exception {
        write("rings/invalid.yml", """
                invalid_ring:
                  item:
                    type: RING
                    id: INVALID_RING
                  mechanics:
                    ARC_SLASH:
                      range: 4
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CombatCatalog.load(contentDirectory));

        assertEquals("Unknown passive mechanic in invalid_ring: ARC_SLASH", error.getMessage());
    }

    @Test
    void acceptsKnownActiveAndPassiveMechanics() throws Exception {
        write("weapons/valid.yml", """
                valid_blade:
                  item:
                    type: SWORD
                    id: VALID_BLADE
                  trigger: SNEAK_RIGHT_CLICK
                  mechanics:
                    ARC_SLASH:
                      range: 4
                """);
        write("rings/valid.yml", """
                valid_ring:
                  item:
                    type: RING
                    id: VALID_RING
                  mechanics:
                    COOLDOWN_MULTIPLIER:
                      multiplier: 0.95
                """);

        CombatCatalog.load(contentDirectory);
    }

    @Test
    void rejectsNanAndInfiniteCacheWeights() throws Exception {
        for (String value : java.util.List.of(".NaN", ".inf", "-.inf")) {
            Path root = contentDirectory.resolve("weight-" + value.replaceAll("[^A-Za-z]", "x"));
            write(root, "caches/invalid.yml", """
                    invalid_cache:
                      item:
                        type: MISCELLANEOUS
                        id: INVALID_CACHE
                      rewards:
                        - type: SWORD
                          id: INVALID_REWARD
                          weight: %s
                    """.formatted(value));

            assertThrows(IllegalArgumentException.class, () -> CombatCatalog.load(root));
        }
    }

    @Test
    void rejectsNanAndInfiniteDropChances() throws Exception {
        for (String value : java.util.List.of(".NaN", ".inf", "-.inf")) {
            Path root = contentDirectory.resolve("chance-" + value.replaceAll("[^A-Za-z]", "x"));
            write(root, "world-drops/invalid.yml", """
                    invalid_drop:
                      scoreboard-tag: INVALID_DROP
                      drops:
                        - type: MATERIAL
                          id: INVALID_MATERIAL
                          chance: %s
                          amount: 1
                    """.formatted(value));

            assertThrows(IllegalArgumentException.class, () -> CombatCatalog.load(root));
        }
    }

    @Test
    void valueObjectsRejectProgrammaticNonFiniteProbabilityAndWeight() {
        CombatCatalog.ItemRef item = new CombatCatalog.ItemRef("MATERIAL", "TEST");

        assertThrows(IllegalArgumentException.class,
                () -> new CombatCatalog.WeightedItem(item, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new CombatCatalog.WeightedItem(item, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new CombatCatalog.DropEntry(item, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CombatCatalog.DropEntry(item, Double.POSITIVE_INFINITY, 1, 1));
    }

    private void write(String relativePath, String yaml) throws Exception {
        write(contentDirectory, relativePath, yaml);
    }

    private void write(Path root, String relativePath, String yaml) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }
}
