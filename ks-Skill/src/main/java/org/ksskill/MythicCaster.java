package org.ksskill;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * 反射封装 MythicMobs 的 {@code BukkitAPIHelper.castSkill(Entity, String)} —— 无编译期依赖
 * （照抄 ks-Eco-RealEstateDungeon 的 MythicSpawner 写法）。
 * 以玩家自身为 caster 施放 metaskill；技能内的 targeter（如 @EntitiesInRadius）围绕玩家生效。
 */
public final class MythicCaster {

    private MythicCaster() {}

    private static volatile Boolean apiAvailable = null;
    private static volatile Method castMethod;

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("MythicMobs") != null;
    }

    /** 对玩家施放指定 MythicMobs 技能。必须主线程调用。失败（缺 MM / 技能不存在 / 反射异常）返回 false。 */
    public static boolean cast(Player caster, String skillName) {
        if (caster == null || skillName == null || skillName.isBlank()) return false;
        if (Boolean.FALSE.equals(apiAvailable)) return false;
        try {
            Class<?> mb = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mb.getMethod("inst").invoke(null);
            if (inst == null) return false;
            Object helper = inst.getClass().getMethod("getAPIHelper").invoke(inst);
            if (helper == null) return false;
            Method m = castMethod;
            if (m == null) {
                m = helper.getClass().getMethod("castSkill", Entity.class, String.class);
                castMethod = m;
            }
            Object r = m.invoke(helper, caster, skillName);
            apiAvailable = true;
            return !(r instanceof Boolean b) || b;
        } catch (Throwable t) {
            apiAvailable = false;
            return false;
        }
    }
}
