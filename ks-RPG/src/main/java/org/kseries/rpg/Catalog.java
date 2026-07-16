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
import java.util.Optional;

final class Catalog {
    enum InputKind { MMOITEM, VANILLA }

    record Input(InputKind kind, String type, String id, Material material, int amount) { }
    record Output(String type, String id, int amount) { }
    record Exchange(String id, String display, List<Input> inputs, Output output) { }

    private final Map<String, Exchange> exchanges;

    private Catalog(Map<String, Exchange> exchanges) {
        this.exchanges = Map.copyOf(exchanges);
    }

    static Catalog load(FileConfiguration config) {
        Map<String, Exchange> loaded = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("exchange");
        if (root == null) return new Catalog(loaded);

        for (String recipeId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(recipeId);
            if (section == null) continue;
            ConfigurationSection inputRoot = section.getConfigurationSection("inputs");
            ConfigurationSection outputRoot = section.getConfigurationSection("output");
            if (inputRoot == null || outputRoot == null) continue;

            List<Input> inputs = new ArrayList<>();
            for (String inputId : inputRoot.getKeys(false)) {
                ConfigurationSection input = inputRoot.getConfigurationSection(inputId);
                if (input == null) continue;
                InputKind kind;
                try {
                    kind = InputKind.valueOf(input.getString("kind", "").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                int amount = input.getInt("amount", 0);
                if (amount < 1) continue;
                if (kind == InputKind.MMOITEM) {
                    String type = input.getString("type", "").trim();
                    String id = input.getString("id", "").trim();
                    if (!type.isEmpty() && !id.isEmpty()) inputs.add(new Input(kind, type, id, null, amount));
                    continue;
                }
                Material material = Material.matchMaterial(input.getString("material", ""));
                if (material != null) inputs.add(new Input(kind, null, null, material, amount));
            }

            String outputType = outputRoot.getString("type", "").trim();
            String outputId = outputRoot.getString("id", "").trim();
            int outputAmount = outputRoot.getInt("amount", 0);
            if (inputs.isEmpty() || outputType.isEmpty() || outputId.isEmpty() || outputAmount < 1) continue;
            loaded.put(recipeId.toLowerCase(Locale.ROOT), new Exchange(recipeId, section.getString("display", recipeId),
                    List.copyOf(inputs), new Output(outputType, outputId, outputAmount)));
        }
        return new Catalog(loaded);
    }

    Optional<Exchange> exchange(String id) {
        return Optional.ofNullable(exchanges.get(id.toLowerCase(Locale.ROOT)));
    }

    Collection<Exchange> exchanges() {
        return exchanges.values();
    }
}
