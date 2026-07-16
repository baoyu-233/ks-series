package org.kstitle;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** 玩家上线时重新应用佩戴称号的属性加成（AttributeModifier/PotionEffect 不跨会话持久化）并检查条件解锁。 */
public final class TitleListener implements Listener {

    private final KsTitle plugin;

    public TitleListener(KsTitle plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        plugin.titleManager().expireOwnerships();
        plugin.attributeApplier().reapplyOnJoin(player);
        plugin.conditionEngine().checkPlayer(player);
    }

    @EventHandler
    public void onCardUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        TitleCardFactory.CardData card = plugin.titleCardFactory().read(item);
        if (card == null) return;

        event.setCancelled(true);
        var player = event.getPlayer();
        var result = plugin.titleManager().grantTemporaryOwnership(
            player.getUniqueId(), card.titleId(), card.durationMillis(), "CARD_TEMP");
        if (!result.success()) {
            player.sendMessage("§c称号卡使用失败: " + result.message());
            return;
        }

        if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else item.setAmount(item.getAmount() - 1);
        player.sendMessage("§a已获得临时称号 #" + card.titleId()
            + " §7到期: §f" + DurationParser.formatExpiry(result.expiresAt()));
    }
}
