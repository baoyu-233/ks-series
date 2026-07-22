package org.kseries.rpg;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CatalogTest {
    @Test
    void rejectsUnknownInputKindInsteadOfDroppingRequirement() throws Exception {
        IllegalArgumentException error = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mystery
                        amount: 8
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);

        assertEquals("Exchange test input scrap has unknown kind: mystery", error.getMessage());
    }

    @Test
    void rejectsMissingMmoItemTypeAndId() throws Exception {
        IllegalArgumentException missingType = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mmoitem
                        id: SCRAP
                        amount: 8
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);
        assertEquals("Exchange test input scrap type must be text", missingType.getMessage());

        IllegalArgumentException missingId = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mmoitem
                        type: MATERIAL
                        amount: 8
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);
        assertEquals("Exchange test input scrap id must be text", missingId.getMessage());
    }

    @Test
    void rejectsInvalidInputAmountFormats() throws Exception {
        IllegalArgumentException textAmount = assertInvalid(recipeWithAmount("\"8\""));
        assertEquals("Exchange test input scrap amount must be a positive integer", textAmount.getMessage());

        IllegalArgumentException fractionalAmount = assertInvalid(recipeWithAmount("1.5"));
        assertEquals("Exchange test input scrap amount must be a positive integer", fractionalAmount.getMessage());

        IllegalArgumentException zeroAmount = assertInvalid(recipeWithAmount("0"));
        assertEquals("Exchange test input scrap amount must be a positive integer", zeroAmount.getMessage());
    }

    @Test
    void rejectsUnknownVanillaMaterial() throws Exception {
        IllegalArgumentException error = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      dust:
                        kind: vanilla
                        material: NOT_A_MATERIAL
                        amount: 8
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);

        assertEquals("Exchange test input dust has unknown vanilla material: NOT_A_MATERIAL", error.getMessage());
    }

    @Test
    void rejectsAirMaterialsWithoutRegistryAccess() throws Exception {
        for (String material : java.util.List.of("AIR", "CAVE_AIR", "VOID_AIR")) {
            IllegalArgumentException error = assertInvalid("""
                    exchange:
                      test:
                        inputs:
                          dust:
                            kind: vanilla
                            material: %s
                            amount: 1
                        output:
                          type: MATERIAL
                          id: OUTPUT
                          amount: 1
                    """.formatted(material));

            assertEquals("Exchange test input dust has unknown vanilla material: " + material,
                    error.getMessage());
        }
    }

    @Test
    void rejectsMalformedInputAndOutputSections() throws Exception {
        IllegalArgumentException scalarInput = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      scrap: MATERIAL:SCRAP
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);
        assertEquals("Exchange test input scrap must be a section", scalarInput.getMessage());

        IllegalArgumentException scalarOutput = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mmoitem
                        type: MATERIAL
                        id: SCRAP
                        amount: 8
                    output: MATERIAL:OUTPUT
                """);
        assertEquals("Exchange test output must be a section", scalarOutput.getMessage());
    }

    @Test
    void rejectsBadOutputFields() throws Exception {
        IllegalArgumentException missingType = assertInvalid(validInputWithOutput("""
                id: OUTPUT
                amount: 1
                """));
        assertEquals("Exchange test output type must be text", missingType.getMessage());

        IllegalArgumentException missingId = assertInvalid(validInputWithOutput("""
                type: MATERIAL
                amount: 1
                """));
        assertEquals("Exchange test output id must be text", missingId.getMessage());

        IllegalArgumentException invalidAmount = assertInvalid(validInputWithOutput("""
                type: MATERIAL
                id: OUTPUT
                amount: many
                """));
        assertEquals("Exchange test output amount must be a positive integer", invalidAmount.getMessage());
    }

    @Test
    void rejectsDuplicateLogicalInputsThatCouldUndercharge() throws Exception {
        IllegalArgumentException error = assertInvalid("""
                exchange:
                  test:
                    inputs:
                      first:
                        kind: mmoitem
                        type: MATERIAL
                        id: SCRAP
                        amount: 4
                      second:
                        kind: mmoitem
                        type: material
                        id: scrap
                        amount: 4
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """);

        assertEquals("Exchange test repeats logical input: second", error.getMessage());
    }

    @Test
    void loadsCompleteValidRecipeWithoutDroppingInputs() throws Exception {
        Catalog catalog = load("""
                exchange:
                  TEST_RECIPE:
                    display: Complete recipe
                    inputs:
                      scrap:
                        kind: mmoitem
                        type: material
                        id: scrap
                        amount: 8
                      redstone:
                        kind: vanilla
                        material: REDSTONE
                        amount: 16
                    output:
                      type: material
                      id: refined_alloy
                      amount: 1
                """);

        Catalog.Exchange recipe = catalog.exchange("test_recipe").orElseThrow();
        assertEquals(2, recipe.inputs().size());
        assertEquals("MATERIAL", recipe.inputs().getFirst().type());
        assertEquals("SCRAP", recipe.inputs().getFirst().id());
        assertEquals("MATERIAL", recipe.output().type());
        assertEquals("REFINED_ALLOY", recipe.output().id());
    }

    private static String recipeWithAmount(String amount) {
        return """
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mmoitem
                        type: MATERIAL
                        id: SCRAP
                        amount: %s
                    output:
                      type: MATERIAL
                      id: OUTPUT
                      amount: 1
                """.formatted(amount);
    }

    private static String validInputWithOutput(String output) {
        return """
                exchange:
                  test:
                    inputs:
                      scrap:
                        kind: mmoitem
                        type: MATERIAL
                        id: SCRAP
                        amount: 8
                    output:
                %s
                """.formatted(output.indent(6));
    }

    private static IllegalArgumentException assertInvalid(String contents) throws Exception {
        return assertThrows(IllegalArgumentException.class, () -> load(contents));
    }

    private static Catalog load(String contents) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(contents);
        return Catalog.load(config);
    }
}
