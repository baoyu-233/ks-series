package org.kstitle;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * {@code %kstitle_use%}（当前佩戴称号展示文本，动画称号按墙钟自动切帧）
 * {@code %kstitle_use_id%}（当前佩戴称号数字ID，未佩戴为空）。
 * 占位符命名沿用 PlayerTitle 的 {@code _use}/{@code _use_id} 后缀，TAB/聊天配置只需把
 * {@code playerTitle} 替换为 {@code kstitle} 即可完成切换。
 */
public final class KsTitlePapiExpansion extends PlaceholderExpansion {

    private final KsTitle plugin;

    public KsTitlePapiExpansion(KsTitle plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() { return "kstitle"; }

    @Override
    public @NotNull String getAuthor() { return "ks-series"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        if (params.equalsIgnoreCase("use")) {
            return plugin.titleManager().currentDisplay(player);
        }
        if (params.equalsIgnoreCase("use_id")) {
            Integer id = plugin.titleManager().getEquipped(player.getUniqueId());
            return id == null ? "" : String.valueOf(id);
        }
        return null;
    }
}
