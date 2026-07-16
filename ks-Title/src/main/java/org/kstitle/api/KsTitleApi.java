package org.kstitle.api;

import org.bukkit.Bukkit;
import org.kstitle.KsTitle;

import java.util.List;
import java.util.UUID;

/**
 * ks-Title 对外静态 API — 替代 {@code cn.handyplus.title.api.PlayerTitleApi}。
 * 供其他插件（如 ks-Skill 的 BindingResolver/TitleBuffCache）反射或直接调用。
 */
public final class KsTitleApi {

    private KsTitleApi() {}

    private static KsTitle plugin() {
        return (KsTitle) Bukkit.getPluginManager().getPlugin("ks-Title");
    }

    /** 玩家当前佩戴的称号ID；未佩戴或插件不可用返回 null。 */
    public static Integer getEquippedTitleId(UUID uuid) {
        KsTitle p = plugin();
        if (p == null) return null;
        return p.titleManager().getEquipped(uuid);
    }

    /** 玩家是否持有某称号（无论是否佩戴）。 */
    public static boolean hasTitle(UUID uuid, int titleId) {
        KsTitle p = plugin();
        return p != null && p.titleManager().hasOwnership(uuid, titleId);
    }

    /** 玩家持有的全部称号ID列表。 */
    public static List<Integer> getOwnedTitleIds(UUID uuid) {
        KsTitle p = plugin();
        if (p == null) return List.of();
        return p.titleManager().listOwned(uuid);
    }
}
