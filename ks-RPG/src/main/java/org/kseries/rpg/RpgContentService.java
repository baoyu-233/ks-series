package org.kseries.rpg;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.kseries.rpg.api.RpgContentApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Keeps all configured RPG item creation and delivery inside ks-RPG. */
final class RpgContentService implements RpgContentApi {
    private final KsRpg plugin;
    private final MmoItemsBridge mmoItems;

    RpgContentService(KsRpg plugin, MmoItemsBridge mmoItems) {
        this.plugin = plugin;
        this.mmoItems = mmoItems;
    }

    @Override
    public List<ContentItem> items() {
        return definitions().values().stream().map(Definition::view).toList();
    }

    @Override
    public Optional<ItemStack> preview(String key) {
        Definition definition = definitions().get(normalize(key));
        if (definition == null) return Optional.empty();
        ItemStack item = definition.item().create(mmoItems);
        return item == null ? Optional.empty() : Optional.of(item.clone());
    }

    @Override
    public GrantResult grant(Player player, String key) {
        Definition definition = definitions().get(normalize(key));
        if (definition == null) return GrantResult.UNKNOWN_ITEM;
        ItemStack item = definition.item().create(mmoItems);
        if (item == null) return GrantResult.ITEM_UNAVAILABLE;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return GrantResult.GRANTED;
        overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        return GrantResult.DROPPED_AT_PLAYER;
    }

    private Map<String, Definition> definitions() {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (CombatCatalog.ContentItem item : plugin.combatCatalog().items()) {
            add(definitions, item.category(), item.item());
        }
        for (Catalog.Exchange exchange : plugin.catalog().exchanges()) {
            for (Catalog.Input input : exchange.inputs()) {
                if (input.kind() == Catalog.InputKind.MMOITEM) add(definitions, "材料", new CombatCatalog.ItemRef(input.type(), input.id()));
            }
            add(definitions, "材料", new CombatCatalog.ItemRef(exchange.output().type(), exchange.output().id()));
        }
        return Map.copyOf(definitions);
    }

    private static void add(Map<String, Definition> definitions, String category, CombatCatalog.ItemRef item) {
        String key = normalize(item.type() + ":" + item.id());
        definitions.putIfAbsent(key, new Definition(new ContentItem(key, category, item.type(), item.id()), item));
    }

    private static String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }

    private record Definition(ContentItem view, CombatCatalog.ItemRef item) { }
}
