package org.kstitle;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.kstitle.model.TitleDef;

import java.util.List;

/**
 * CONDITION 类称号的自动解锁判定。当前实现 PERMISSION（拥有权限节点即解锁），
 * 其余 conditionType（如统计阈值/累计余额）预留扩展点：新增一个 case 分支+判定方法即可。
 */
public final class ConditionEngine {

    private final KsTitle plugin;

    public ConditionEngine(KsTitle plugin) {
        this.plugin = plugin;
    }

    /** 周期性任务（每 60s）+ 玩家上线时调用，检查在线玩家是否满足条件类称号解锁。 */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayer(player);
        }
    }

    public void checkPlayer(Player player) {
        List<TitleDef> defs = plugin.titleManager().listDefs();
        for (TitleDef def : defs) {
            if (!def.isCondition() || !def.enabled()) continue;
            if (plugin.titleManager().hasOwnership(player.getUniqueId(), def.id())) continue;
            if (meetsCondition(player, def)) {
                if (plugin.titleManager().grantOwnership(player.getUniqueId(), def.id(), "CONDITION")) {
                    player.sendMessage("§a[称号] 你已达成条件解锁称号: §f" + def.displayName());
                }
            }
        }
    }

    private boolean meetsCondition(Player player, TitleDef def) {
        return switch (def.conditionType() == null ? "" : def.conditionType()) {
            case "PERMISSION" -> !def.conditionValue().isBlank() && player.hasPermission(def.conditionValue());
            default -> false;
        };
    }
}
