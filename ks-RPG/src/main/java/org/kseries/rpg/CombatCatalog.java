package org.kseries.rpg;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Immutable, file-backed combat content. Item identities exist only in content YAML files. */
final class CombatCatalog {
    private static final java.util.Set<String> ACTIVE_MECHANIC_TYPES = java.util.Set.of(
            "ARC_SLASH", "MARK_ARROW", "HAMMER_QUAKE", "HEAVY_QUAKE", "RELAY_DASH",
            "ANCHOR_GUARD", "SIGNAL_AURA", "PIONEER_RESCUE");
    private static final java.util.Set<String> PASSIVE_MECHANIC_TYPES = java.util.Set.of(
            "LAST_STAND_ABSORPTION", "LOW_HEALTH_RESISTANCE", "NEXT_ATTACK_ENABLE",
            "COOLDOWN_MULTIPLIER", "MARK_FOCUS_DAMAGE", "MARK_RESONANCE", "HUNTER_MARK_DAMAGE");

    enum Trigger {
        SNEAK_RIGHT_CLICK,
        SNEAK_SWAP_HANDS
    }

    record ItemRef(String type, String id) {
        ItemRef {
            type = required(type, "item type");
            id = required(id, "item id");
        }

        boolean matches(ItemStack item, MmoItemsBridge mmoItems) {
            return mmoItems.matches(item, type, id);
        }

        ItemStack create(MmoItemsBridge mmoItems) {
            return mmoItems.create(type, id);
        }
    }

    record Mechanic(String type, Map<String, Object> values) {
        Mechanic {
            type = required(type, "mechanic type").toUpperCase(Locale.ROOT);
            values = Map.copyOf(values);
        }

        double number(String key, double fallback) {
            Object value = values.get(key);
            return value instanceof Number number ? number.doubleValue() : fallback;
        }

        int whole(String key, int fallback) {
            return (int) Math.round(number(key, fallback));
        }

        long milliseconds(String key, long fallback) {
            return Math.round(number(key, fallback));
        }

        String text(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String text && !text.isBlank() ? text : fallback;
        }
    }

    record ActiveDefinition(String key, ItemRef item, Trigger trigger, int cooldownSeconds, List<Mechanic> mechanics) {
        ActiveDefinition {
            key = required(key, "definition key");
            cooldownSeconds = Math.max(0, cooldownSeconds);
            mechanics = List.copyOf(mechanics);
            if (mechanics.isEmpty()) throw new IllegalArgumentException("Active definition " + key + " has no mechanics");
        }
    }

    record PassiveDefinition(String key, String category, ItemRef item, List<Mechanic> mechanics) {
        PassiveDefinition {
            key = required(key, "definition key");
            category = required(category, "category");
            mechanics = List.copyOf(mechanics);
            if (mechanics.isEmpty()) throw new IllegalArgumentException("Passive definition " + key + " has no mechanics");
        }
    }

    record WeightedItem(ItemRef item, double weight) {
        WeightedItem {
            if (!Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("Reward weight must be finite and positive");
            }
        }
    }

    record CacheDefinition(String key, ItemRef item, List<WeightedItem> rewards) {
        CacheDefinition {
            key = required(key, "definition key");
            rewards = List.copyOf(rewards);
            if (rewards.isEmpty()) throw new IllegalArgumentException("Cache definition " + key + " has no rewards");
        }
    }

    record DropEntry(ItemRef item, double chance, int minimum, int maximum) {
        DropEntry {
            if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
                throw new IllegalArgumentException("Drop chance must be finite and between 0 and 1");
            }
            if (minimum < 1 || maximum < minimum) throw new IllegalArgumentException("Invalid drop amount range");
        }
    }

    record MobDropDefinition(String key, String scoreboardTag, List<DropEntry> drops) {
        MobDropDefinition {
            key = required(key, "definition key");
            scoreboardTag = required(scoreboardTag, "scoreboard tag");
            drops = List.copyOf(drops);
        }
    }

    record ContentItem(String category, ItemRef item) { }

    private final List<ActiveDefinition> actives;
    private final List<PassiveDefinition> passives;
    private final List<CacheDefinition> caches;
    private final List<MobDropDefinition> mobDrops;

    private CombatCatalog(List<ActiveDefinition> actives, List<PassiveDefinition> passives,
                          List<CacheDefinition> caches, List<MobDropDefinition> mobDrops) {
        this.actives = List.copyOf(actives);
        this.passives = List.copyOf(passives);
        this.caches = List.copyOf(caches);
        this.mobDrops = List.copyOf(mobDrops);
    }

    static CombatCatalog empty() {
        return new CombatCatalog(List.of(), List.of(), List.of(), List.of());
    }

    static CombatCatalog load(Path contentDirectory) throws IOException {
        if (!Files.isDirectory(contentDirectory)) return empty();
        List<ActiveDefinition> actives = new ArrayList<>();
        List<PassiveDefinition> passives = new ArrayList<>();
        List<CacheDefinition> caches = new ArrayList<>();
        List<MobDropDefinition> mobDrops = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(contentDirectory)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path file : files) {
                Path relative = contentDirectory.relativize(file);
                if (relative.getNameCount() < 2) continue;
                String category = relative.getName(0).toString().toLowerCase(Locale.ROOT);
                YamlConfiguration yaml = loadYaml(file);
                switch (category) {
                    case "weapons" -> loadActives(yaml, category, actives);
                    case "talismans" -> loadTalismans(yaml, actives, passives);
                    case "rings" -> loadPassives(yaml, passives);
                    case "caches" -> loadCaches(yaml, caches);
                    case "world-drops" -> loadMobDrops(yaml, mobDrops);
                    default -> { }
                }
            }
        }
        return new CombatCatalog(actives, passives, caches, mobDrops);
    }

    private static YamlConfiguration loadYaml(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
            return yaml;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot parse " + file.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    Optional<ActiveDefinition> active(ItemStack item, Trigger trigger, MmoItemsBridge mmoItems) {
        return actives.stream().filter(definition -> definition.trigger() == trigger)
                .filter(definition -> definition.item().matches(item, mmoItems)).findFirst();
    }

    Optional<ActiveDefinition> equippedActive(List<ItemStack> equipped, Trigger trigger, MmoItemsBridge mmoItems) {
        return actives.stream().filter(definition -> definition.trigger() == trigger)
                .filter(definition -> equipped.stream().anyMatch(item -> definition.item().matches(item, mmoItems))).findFirst();
    }

    Optional<CacheDefinition> cache(ItemStack item, MmoItemsBridge mmoItems) {
        return caches.stream().filter(definition -> definition.item().matches(item, mmoItems)).findFirst();
    }

    List<Mechanic> passives(List<ItemStack> equipped, String mechanicType, MmoItemsBridge mmoItems) {
        String expected = mechanicType.toUpperCase(Locale.ROOT);
        List<Mechanic> matches = new ArrayList<>();
        for (PassiveDefinition passive : passives) {
            if (equipped.stream().noneMatch(item -> passive.item().matches(item, mmoItems))) continue;
            passive.mechanics().stream().filter(mechanic -> mechanic.type().equals(expected)).forEach(matches::add);
        }
        return List.copyOf(matches);
    }

    List<MobDropDefinition> dropsForTag(String scoreboardTag) {
        return mobDrops.stream().filter(definition -> definition.scoreboardTag().equals(scoreboardTag)).toList();
    }

    List<ContentItem> items() {
        Map<String, ContentItem> unique = new LinkedHashMap<>();
        for (ActiveDefinition active : actives) addItem(unique, active.trigger() == Trigger.SNEAK_RIGHT_CLICK ? "武器" : "护符", active.item());
        for (PassiveDefinition passive : passives) addItem(unique, passive.category(), passive.item());
        for (CacheDefinition cache : caches) {
            addItem(unique, "装备箱", cache.item());
            cache.rewards().forEach(reward -> addItem(unique, "装备箱奖励", reward.item()));
        }
        for (MobDropDefinition table : mobDrops) table.drops().forEach(drop -> addItem(unique, "野外掉落", drop.item()));
        return List.copyOf(unique.values());
    }

    private static void loadActives(YamlConfiguration yaml, String category, List<ActiveDefinition> output) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            Trigger trigger = Trigger.valueOf(required(section.getString("trigger"), key + ".trigger").toUpperCase(Locale.ROOT));
            if (category.equals("weapons") && trigger != Trigger.SNEAK_RIGHT_CLICK) {
                throw new IllegalArgumentException("Weapon " + key + " must use SNEAK_RIGHT_CLICK");
            }
            if (category.equals("talismans") && trigger != Trigger.SNEAK_SWAP_HANDS) {
                throw new IllegalArgumentException("Talisman " + key + " must use SNEAK_SWAP_HANDS");
            }
            output.add(new ActiveDefinition(key, item(section, key), trigger, section.getInt("cooldown-seconds", 0),
                    mechanics(section, key, ACTIVE_MECHANIC_TYPES, "active")));
        }
    }

    private static void loadPassives(YamlConfiguration yaml, List<PassiveDefinition> output) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section != null) output.add(new PassiveDefinition(key, "饰品", item(section, key),
                    mechanics(section, key, PASSIVE_MECHANIC_TYPES, "passive")));
        }
    }

    private static void loadTalismans(YamlConfiguration yaml, List<ActiveDefinition> actives, List<PassiveDefinition> passives) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            if (section.contains("trigger")) {
                Trigger trigger = Trigger.valueOf(required(section.getString("trigger"), key + ".trigger").toUpperCase(Locale.ROOT));
                if (trigger != Trigger.SNEAK_SWAP_HANDS) throw new IllegalArgumentException("Talisman " + key + " must use SNEAK_SWAP_HANDS");
                actives.add(new ActiveDefinition(key, item(section, key), trigger, section.getInt("cooldown-seconds", 0),
                        mechanics(section, key, ACTIVE_MECHANIC_TYPES, "active")));
            } else {
                passives.add(new PassiveDefinition(key, "护符", item(section, key),
                        mechanics(section, key, PASSIVE_MECHANIC_TYPES, "passive")));
            }
        }
    }

    private static void loadCaches(YamlConfiguration yaml, List<CacheDefinition> output) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            List<WeightedItem> rewards = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("rewards")) {
                rewards.add(new WeightedItem(item(raw, key + ".rewards"), number(raw.get("weight"), "weight")));
            }
            output.add(new CacheDefinition(key, item(section, key), rewards));
        }
    }

    private static void loadMobDrops(YamlConfiguration yaml, List<MobDropDefinition> output) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            List<DropEntry> drops = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("drops")) {
                Object amount = raw.get("amount");
                Object minimumValue = raw.containsKey("minimum") ? raw.get("minimum") : amount;
                Object maximumValue = raw.containsKey("maximum") ? raw.get("maximum") : amount;
                int minimum = (int) Math.round(number(minimumValue, "minimum"));
                int maximum = (int) Math.round(number(maximumValue, "maximum"));
                drops.add(new DropEntry(item(raw, key + ".drops"), number(raw.get("chance"), "chance"), minimum, maximum));
            }
            output.add(new MobDropDefinition(key, required(section.getString("scoreboard-tag"), key + ".scoreboard-tag"), drops));
        }
    }

    private static List<Mechanic> mechanics(ConfigurationSection section, String key,
                                             java.util.Set<String> allowedTypes, String mechanicKind) {
        ConfigurationSection mechanics = section.getConfigurationSection("mechanics");
        if (mechanics == null) throw new IllegalArgumentException("Missing mechanics in " + key);
        List<Mechanic> loaded = new ArrayList<>();
        for (String type : mechanics.getKeys(false)) {
            ConfigurationSection values = mechanics.getConfigurationSection(type);
            if (values == null) throw new IllegalArgumentException("Mechanic " + key + "." + type + " must be a section");
            String normalizedType = required(type, key + " mechanic type").toUpperCase(Locale.ROOT);
            if (!allowedTypes.contains(normalizedType)) {
                throw new IllegalArgumentException("Unknown " + mechanicKind + " mechanic in " + key + ": " + type);
            }
            loaded.add(new Mechanic(normalizedType, new LinkedHashMap<>(values.getValues(false))));
        }
        return loaded;
    }

    private static ItemRef item(ConfigurationSection section, String key) {
        ConfigurationSection item = section.getConfigurationSection("item");
        if (item == null) throw new IllegalArgumentException("Missing item section in " + key);
        return new ItemRef(item.getString("type"), item.getString("id"));
    }

    private static ItemRef item(Map<?, ?> values, String key) {
        return new ItemRef(string(values.get("type"), key + ".type"), string(values.get("id"), key + ".id"));
    }

    private static double number(Object value, String key) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return number.doubleValue();
        throw new IllegalArgumentException("Missing or invalid number " + key);
    }

    private static String string(Object value, String key) {
        if (value instanceof String text) return required(text, key);
        throw new IllegalArgumentException("Missing or invalid text " + key);
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static void addItem(Map<String, ContentItem> items, String category, ItemRef item) {
        items.putIfAbsent((item.type() + ":" + item.id()).toLowerCase(Locale.ROOT), new ContentItem(category, item));
    }
}
