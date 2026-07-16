package org.kseco;

import java.util.*;

/**
 * 玩家功能开放控制（逐步开放经济插件用）。
 * 拥有 kseco.admin 权限的玩家/管理员 session 不受此限制，调用方需自行判断后再 isOpen()。
 * 暂存箱(storage)、纳税记录查询不在此名单中，始终对玩家开放。
 */
public final class FeatureGateManager {

    /** 可关闭的 feature key 列表，与 EcoGuiMainMenu 槛位 / player.html 侧栏 Tab 一一对应。 */
    public static final List<String> FEATURE_KEYS = List.of(
            "market", "bank", "transfer", "enterprise", "bidding", "trade", "exchange",
            "realestate", "dungeon", "politic", "blindbox", "ent_blindbox", "limited_sale", "compensation", "invites"
    );

    /** 默认开放的 key——市场及其直接配套的基础功能（兑换、个人盲盒）。 */
    private static final Set<String> DEFAULT_OPEN = Set.of("market", "exchange", "blindbox", "limited_sale", "compensation");

    private final KsEco plugin;
    private final Map<String, Boolean> state = new LinkedHashMap<>();

    public FeatureGateManager(KsEco plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig().getConfigurationSection("player-features");
        state.clear();
        for (String key : FEATURE_KEYS) {
            boolean def = DEFAULT_OPEN.contains(key);
            state.put(key, cfg != null ? cfg.getBoolean(key, def) : def);
        }
    }

    public boolean isOpen(String featureId) {
        return state.getOrDefault(featureId, false);
    }

    public Map<String, Boolean> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    public void setOpen(String featureId, boolean open) {
        if (!FEATURE_KEYS.contains(featureId)) return;
        state.put(featureId, open);
        plugin.getConfig().set("player-features." + featureId, open);
        plugin.saveConfig();
    }
}
