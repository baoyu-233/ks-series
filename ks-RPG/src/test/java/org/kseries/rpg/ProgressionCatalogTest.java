package org.kseries.rpg;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProgressionCatalogTest {
    @Test
    void rejectsGateThatReferencesUnknownProof() throws Exception {
        YamlConfiguration config = yaml("""
                combat-proofs:
                  elite_clear:
                    display: Elite Clear
                gates:
                  relic_breakthrough:
                    required-proofs:
                      - elite_celar
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProgressionCatalog.load(config));

        assertEquals("Proof gate relic_breakthrough references unknown combat proof: elite_celar",
                error.getMessage());
    }

    @Test
    void rejectsInvalidProofIdInsteadOfSilentlyDroppingIt() throws Exception {
        YamlConfiguration config = yaml("""
                combat-proofs:
                  invalid proof:
                    display: Invalid
                """);

        assertThrows(IllegalArgumentException.class, () -> ProgressionCatalog.load(config));
    }

    @Test
    void preservesDeclaredProofRequirement() throws Exception {
        YamlConfiguration config = yaml("""
                combat-proofs:
                  elite_clear:
                    display: Elite Clear
                gates:
                  relic_breakthrough:
                    display: Relic Breakthrough
                    required-proofs:
                      - ELITE_CLEAR
                """);

        ProgressionCatalog catalog = ProgressionCatalog.load(config);

        assertEquals(java.util.List.of("elite_clear"),
                catalog.gate("relic_breakthrough").orElseThrow().requiredProofs());
    }


    @Test
    void rejectsEmptyRequiredProofsInsteadOfFailOpen() throws Exception {
        YamlConfiguration config = yaml("""
                combat-proofs:
                  elite_clear:
                    display: Elite Clear
                gates:
                  relic_breakthrough:
                    required-proofs: []
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProgressionCatalog.load(config));
        assertEquals("Proof gate relic_breakthrough required-proofs must not be empty", error.getMessage());
    }

    @Test
    void rejectsNonListRequiredProofs() throws Exception {
        YamlConfiguration config = yaml("""
                combat-proofs:
                  elite_clear:
                    display: Elite Clear
                gates:
                  relic_breakthrough:
                    required-proofs: elite_clear
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProgressionCatalog.load(config));
        assertEquals("Proof gate relic_breakthrough required-proofs must be a list", error.getMessage());
    }

    private static YamlConfiguration yaml(String contents) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(contents);
        return config;
    }
}
