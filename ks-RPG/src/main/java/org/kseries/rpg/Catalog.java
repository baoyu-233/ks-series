package org.kseries.rpg;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class Catalog {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    enum InputKind { MMOITEM, VANILLA }

    record Input(InputKind kind, String type, String id, Material material, int amount) {
        Input {
            Objects.requireNonNull(kind, "input kind");
            if (amount < 1) throw new IllegalArgumentException("input amount must be positive");
            if (kind == InputKind.MMOITEM) {
                type = requireIdentifier(type, "MMOItems input type").toUpperCase(Locale.ROOT);
                id = requireIdentifier(id, "MMOItems input id").toUpperCase(Locale.ROOT);
                if (material != null) throw new IllegalArgumentException("MMOItems input cannot declare material");
            } else {
                Objects.requireNonNull(material, "vanilla input material");
                if (isAir(material)) throw new IllegalArgumentException("vanilla input material cannot be air");
                if (type != null || id != null) throw new IllegalArgumentException("vanilla input cannot declare type or id");
            }
        }

        String identity() {
            return kind == InputKind.MMOITEM ? kind + ":" + type + ":" + id : kind + ":" + material.name();
        }
    }

    record Output(String type, String id, int amount) {
        Output {
            type = requireIdentifier(type, "output type").toUpperCase(Locale.ROOT);
            id = requireIdentifier(id, "output id").toUpperCase(Locale.ROOT);
            if (amount < 1) throw new IllegalArgumentException("output amount must be positive");
        }
    }

    record Exchange(String id, String display, List<Input> inputs, Output output) {
        Exchange {
            id = requireIdentifier(id, "exchange id").toLowerCase(Locale.ROOT);
            display = requireText(display, "exchange " + id + " display");
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) throw new IllegalArgumentException("Exchange " + id + " must declare inputs");
            Objects.requireNonNull(output, "exchange output");
        }
    }

    private final Map<String, Exchange> exchanges;

    private Catalog(Map<String, Exchange> exchanges) {
        this.exchanges = Map.copyOf(exchanges);
    }

    static Catalog load(FileConfiguration config) {
        Map<String, Exchange> loaded = new LinkedHashMap<>();
        Object rawRoot = config.get("exchange");
        if (rawRoot == null) return new Catalog(loaded);
        if (!(rawRoot instanceof ConfigurationSection root)) {
            throw new IllegalArgumentException("exchange must be a section");
        }

        for (String recipeId : root.getKeys(false)) {
            String normalizedRecipeId = requireIdentifier(recipeId, "exchange id").toLowerCase(Locale.ROOT);
            ConfigurationSection section = requireSection(root, recipeId, "Exchange " + normalizedRecipeId);
            ConfigurationSection inputRoot = requireSection(section, "inputs",
                    "Exchange " + normalizedRecipeId + " inputs");
            ConfigurationSection outputRoot = requireSection(section, "output",
                    "Exchange " + normalizedRecipeId + " output");
            if (inputRoot.getKeys(false).isEmpty()) {
                throw new IllegalArgumentException("Exchange " + normalizedRecipeId + " must declare inputs");
            }

            List<Input> inputs = new ArrayList<>();
            Set<String> inputNames = new java.util.HashSet<>();
            Set<String> inputIdentities = new java.util.HashSet<>();
            for (String inputId : inputRoot.getKeys(false)) {
                String normalizedInputId = requireIdentifier(inputId,
                        "input id in exchange " + normalizedRecipeId).toLowerCase(Locale.ROOT);
                if (!inputNames.add(normalizedInputId)) {
                    throw new IllegalArgumentException("Exchange " + normalizedRecipeId
                            + " repeats input id after normalization: " + inputId);
                }
                ConfigurationSection input = requireSection(inputRoot, inputId,
                        "Exchange " + normalizedRecipeId + " input " + normalizedInputId);
                Input parsed = parseInput(input, normalizedRecipeId, normalizedInputId);
                if (!inputIdentities.add(parsed.identity())) {
                    throw new IllegalArgumentException("Exchange " + normalizedRecipeId
                            + " repeats logical input: " + normalizedInputId);
                }
                inputs.add(parsed);
            }

            requireOnlyKeys(outputRoot, Set.of("type", "id", "amount"),
                    "Exchange " + normalizedRecipeId + " output");
            Output output = new Output(
                    requireIdentifierValue(outputRoot, "type", "Exchange " + normalizedRecipeId + " output type"),
                    requireIdentifierValue(outputRoot, "id", "Exchange " + normalizedRecipeId + " output id"),
                    requirePositiveInt(outputRoot, "amount", "Exchange " + normalizedRecipeId + " output amount"));
            String display = optionalDisplay(section, recipeId, normalizedRecipeId);
            Exchange exchange = new Exchange(normalizedRecipeId, display, inputs, output);
            if (loaded.putIfAbsent(normalizedRecipeId, exchange) != null) {
                throw new IllegalArgumentException("Duplicate exchange id after normalization: " + recipeId);
            }
        }
        return new Catalog(loaded);
    }

    private static Input parseInput(ConfigurationSection input, String recipeId, String inputId) {
        String context = "Exchange " + recipeId + " input " + inputId;
        String configuredKind = requireIdentifierValue(input, "kind", context + " kind");
        final InputKind kind;
        try {
            kind = InputKind.valueOf(configuredKind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(context + " has unknown kind: " + configuredKind, ex);
        }
        int amount = requirePositiveInt(input, "amount", context + " amount");
        if (kind == InputKind.MMOITEM) {
            requireOnlyKeys(input, Set.of("kind", "type", "id", "amount"), context);
            return new Input(kind,
                    requireIdentifierValue(input, "type", context + " type"),
                    requireIdentifierValue(input, "id", context + " id"), null, amount);
        }

        requireOnlyKeys(input, Set.of("kind", "material", "amount"), context);
        String materialId = requireIdentifierValue(input, "material", context + " material");
        Material material = Material.matchMaterial(materialId);
        if (material == null || isAir(material)) {
            throw new IllegalArgumentException(context + " has unknown vanilla material: " + materialId);
        }
        return new Input(kind, null, null, material, amount);
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String key, String context) {
        Object value = parent.get(key);
        if (value instanceof ConfigurationSection section) return section;
        throw new IllegalArgumentException(context + " must be a section");
    }

    private static void requireOnlyKeys(ConfigurationSection section, Set<String> allowed, String context) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(context + " has unknown field: " + key);
        }
    }

    private static int requirePositiveInt(ConfigurationSection section, String key, String context) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(context + " must be a positive integer");
        }
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integer || integer < 1 || integer > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(context + " must be a positive integer");
        }
        return (int) integer;
    }

    private static String requireIdentifierValue(ConfigurationSection section, String key, String context) {
        Object value = section.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(context + " must be text");
        return requireIdentifier(text, context);
    }

    private static String optionalDisplay(ConfigurationSection section, String fallback, String recipeId) {
        Object value = section.get("display");
        if (value == null) return fallback;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Exchange " + recipeId + " display must be text");
        }
        return requireText(text, "exchange " + recipeId + " display");
    }

    private static String requireIdentifier(String value, String context) {
        String normalized = requireText(value, context);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(context + " has invalid format: " + value);
        }
        return normalized;
    }

    private static String requireText(String value, String context) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(context + " must not be blank");
        return value.trim();
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    Optional<Exchange> exchange(String id) {
        return Optional.ofNullable(exchanges.get(id.toLowerCase(Locale.ROOT)));
    }

    Collection<Exchange> exchanges() {
        return exchanges.values();
    }
}
