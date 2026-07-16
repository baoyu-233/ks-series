package org.kstitle;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.kstitle.model.TitleDef;

import java.util.ArrayList;
import java.util.List;

public final class TitleCardFactory {

    private final KsTitle plugin;
    private final NamespacedKey cardKey;
    private final NamespacedKey titleKey;
    private final NamespacedKey durationKey;

    public TitleCardFactory(KsTitle plugin) {
        this.plugin = plugin;
        this.cardKey = new NamespacedKey(plugin, "title_card");
        this.titleKey = new NamespacedKey(plugin, "title_card_title_id");
        this.durationKey = new NamespacedKey(plugin, "title_card_duration_ms");
    }

    public ItemStack create(int titleId, long durationMillis, int amount) {
        TitleDef def = plugin.titleManager().getDef(titleId);
        ItemStack item = new ItemStack(Material.NAME_TAG, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(LegacyText.colorize("&b称号临时卡 &7#" + titleId));

        List<String> lore = new ArrayList<>();
        lore.add(LegacyText.colorize("&7称号: " + (def == null ? ("#" + titleId) : def.displayName())));
        lore.add(LegacyText.colorize("&7时长: &e" + DurationParser.formatDuration(durationMillis)));
        lore.add("");
        lore.add(LegacyText.colorize("&a右键使用后获得临时称号"));
        lore.add(LegacyText.colorize("&8已有临时称号会自动续期"));
        meta.setLore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(cardKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(titleKey, PersistentDataType.INTEGER, titleId);
        pdc.set(durationKey, PersistentDataType.LONG, durationMillis);
        item.setItemMeta(meta);
        return item;
    }

    public CardData read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Byte marker = pdc.get(cardKey, PersistentDataType.BYTE);
        Integer titleId = pdc.get(titleKey, PersistentDataType.INTEGER);
        Long duration = pdc.get(durationKey, PersistentDataType.LONG);
        if (marker == null || marker != (byte) 1 || titleId == null || duration == null || duration <= 0L) return null;
        return new CardData(titleId, duration);
    }

    public void consumeOne(ItemStack item) {
        if (item == null) return;
        int amount = item.getAmount();
        if (amount <= 1) item.setAmount(0);
        else item.setAmount(amount - 1);
    }

    public record CardData(int titleId, long durationMillis) {}
}
