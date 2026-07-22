package org.kseries.cinematic;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class CinematicCatalog {
    record Point(double x, double y, double z, float yaw, float pitch) { }

    record Trigger(
            String kind,
            String type,
            String id,
            Material material,
            String key,
            String value,
            boolean consume,
            String tokenName,
            List<String> tokenLore
    ) { }

    record Scene(String schematic, String world, int spacing, int max, int pasteY, int radius, int timeout) { }
    record Action(int at, String type, Map<String, Object> data) { }
    record Story(String id, String display, Trigger trigger, Scene scene, Map<String, Point> points,
                 List<Action> actions, boolean replayable, Path file) { }

    private final Map<String, Story> stories;

    private CinematicCatalog(Map<String, Story> stories) {
        this.stories = Map.copyOf(stories);
    }

    static CinematicCatalog empty() {
        return new CinematicCatalog(Map.of());
    }

    static CinematicCatalog load(Path root) throws Exception {
        if (!Files.isDirectory(root)) return empty();

        Map<String, Story> loaded = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(path -> path.getFileName().toString().equalsIgnoreCase("story.yml"))
                    .sorted().toList()) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());

                String id = required(yaml.getString("id"), "id").toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_.-]+") || loaded.containsKey(id)) {
                    throw new IllegalArgumentException("Invalid or duplicate story id " + id);
                }

                Trigger trigger = loadTrigger(section(yaml, "trigger"));
                ConfigurationSection instance = section(yaml, "instance");
                Scene scene = new Scene(
                        required(instance.getString("schematic"), "instance.schematic"),
                        instance.getString("world", "ks-dungeon-world"),
                        instance.getInt("spacing", 5000),
                        instance.getInt("max-grids", 2),
                        instance.getInt("paste-y", 64),
                        instance.getInt("radius", 96),
                        instance.getInt("timeout-seconds", 180)
                );

                Map<String, Point> points = new LinkedHashMap<>();
                ConfigurationSection pointRoot = yaml.getConfigurationSection("points");
                if (pointRoot != null) {
                    for (String name : pointRoot.getKeys(false)) {
                        if (name.equals("__editor_origin")) continue;
                        ConfigurationSection point = section(pointRoot, name);
                        points.put(name, new Point(
                                point.getDouble("x"), point.getDouble("y"), point.getDouble("z"),
                                (float) point.getDouble("yaw"), (float) point.getDouble("pitch")
                        ));
                    }
                }

                List<Action> actions = new ArrayList<>();
                for (Map<?, ?> raw : yaml.getMapList("timeline")) {
                    Object at = raw.get("at");
                    Object type = raw.get("type");
                    if (!(at instanceof Number number) || !(type instanceof String text) || number.intValue() < 0) {
                        throw new IllegalArgumentException("Invalid timeline action " + id);
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        values.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    actions.add(new Action(number.intValue(), text.toUpperCase(Locale.ROOT), Map.copyOf(values)));
                }
                actions.sort(Comparator.comparingInt(Action::at));
                loaded.put(id, new Story(id, yaml.getString("display", id), trigger, scene,
                        Map.copyOf(points), List.copyOf(actions), yaml.getBoolean("replayable"), file));
            }
        }
        return new CinematicCatalog(loaded);
    }

    Optional<Story> get(String id) {
        return Optional.ofNullable(stories.get(id == null ? "" : id.toLowerCase(Locale.ROOT)));
    }

    List<Story> list() {
        return stories.values().stream().toList();
    }

    private static Trigger loadTrigger(ConfigurationSection trigger) {
        String kind = required(trigger.getString("kind"), "trigger.kind").toUpperCase(Locale.ROOT);
        Material material = trigger.contains("material") ? material(trigger.getString("material")) : null;
        String type = trigger.getString("type", "").trim();
        String id = trigger.getString("id", "").trim();
        String key = trigger.getString("key", "").trim().toLowerCase(Locale.ROOT);
        String value = trigger.getString("value", "").trim();
        String tokenName = trigger.getString("token-name", "&f" + id);
        List<String> tokenLore = List.copyOf(trigger.getStringList("token-lore"));

        switch (kind) {
            case "MMOITEM" -> {
                if (type.isBlank() || id.isBlank()) throw new IllegalArgumentException("MMOITEM trigger needs type/id");
            }
            case "VANILLA" -> {
                if (material == null) throw new IllegalArgumentException("VANILLA trigger needs material");
            }
            case "PDC" -> {
                if (material == null || key.isBlank() || value.isBlank()) {
                    throw new IllegalArgumentException("PDC trigger needs material/key/value");
                }
                if (!key.matches("[a-z0-9_.-]+")) {
                    throw new IllegalArgumentException("PDC trigger key contains unsupported characters");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported trigger kind " + kind);
        }

        return new Trigger(kind, type, id, material, key, value, trigger.getBoolean("consume"), tokenName, tokenLore);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String name) {
        ConfigurationSection section = parent.getConfigurationSection(name);
        if (section == null) throw new IllegalArgumentException("Missing " + name);
        return section;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value.trim();
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(required(name, "material"));
        if (material == null) throw new IllegalArgumentException("Unknown material " + name);
        return material;
    }
}
