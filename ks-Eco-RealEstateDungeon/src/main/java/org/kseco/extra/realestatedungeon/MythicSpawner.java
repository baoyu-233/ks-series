package org.kseco.extra.realestatedungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * MythicMobs 刷怪封装 —— 纯反射 + Bukkit，无编译期依赖（缺 MythicMobs 也能编译/加载）。
 *
 * <p>优先用 MythicMobs API（可带等级），失败回退控制台命令 {@code mm mobs spawn}（不带等级，最稳，
 * 但拿不到实体引用，无法用于 boss 存活检测）。</p>
 *
 * 标记约定（建图时在刷怪点放告示牌）：
 * <ul>
 *   <li>第 1 行 {@code [mm]}，第 2 行 {@code 怪物名:等级}（如 {@code SkeletonKing:10}），等级可省略</li>
 *   <li>第 3 行可选填 {@code boss}（不分大小写）标记为本副本的 boss——
 *       该怪死亡后副本会自动判定通关，需要走 API 刷怪路径（命令兜底拿不到实体UUID，无法追踪）</li>
 *   <li>第 1 行 {@code [spawn]} 标记玩家出生点（由 InstanceManager 处理，不在本类）</li>
 * </ul>
 */
public final class MythicSpawner {

    private MythicSpawner() {}

    private static volatile Boolean apiAvailable = null;

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("MythicMobs") != null;
    }

    /**
     * 在指定位置刷一只 MythicMob。必须在主线程调用。
     * @return 刷出实体的 UUID；失败返回 null。命令兜底路径成功也返回 null（拿不到实体引用）。
     */
    public static UUID spawn(Plugin plugin, String mobName, Location loc, int level) {
        if (mobName == null || mobName.isBlank() || loc == null) return null;
        UUID uuid = spawnViaMobExecutor(mobName, loc, level);
        if (uuid != null) return uuid;
        uuid = spawnViaApi(plugin, mobName, loc, level);
        if (uuid != null) return uuid;
        if (spawnViaCommand(mobName, loc)) {
            UUID nearby = findNearbyMythicMob(mobName, loc, 6.0);
            if (nearby != null) return nearby;
        }
        return null;
    }

    private static UUID spawnViaMobExecutor(String mobName, Location loc, int level) {
        try {
            Class<?> mbClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mbClass.getMethod("inst").invoke(null);
            if (inst == null) return null;
            Object mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            if (mobManager == null) return null;
            Method m = mobManager.getClass().getMethod("spawnMob", String.class, Location.class, double.class);
            Object activeMob = m.invoke(mobManager, mobName, loc, (double) level);
            return extractBukkitUuid(activeMob);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static UUID findNearbyMythicMob(String mobName, Location loc, double radius) {
        if (mobName == null || mobName.isBlank() || loc == null || loc.getWorld() == null) return null;
        try {
            Class<?> mbClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mbClass.getMethod("inst").invoke(null);
            Object mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object activeMobs = mobManager.getClass().getMethod("getActiveMobs").invoke(mobManager);
            if (!(activeMobs instanceof Iterable<?> mobs)) return null;
            String want = mobName.toLowerCase(java.util.Locale.ROOT);
            UUID best = null;
            double bestDist = radius * radius;
            for (Object activeMob : mobs) {
                String type = mythicType(activeMob);
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).equals(want)) continue;
                UUID uuid = extractBukkitUuid(activeMob);
                Entity entity = uuid == null ? null : Bukkit.getEntity(uuid);
                if (entity == null || entity.getWorld() == null || !entity.getWorld().equals(loc.getWorld())) continue;
                double dist = entity.getLocation().distanceSquared(loc);
                if (dist <= bestDist) {
                    bestDist = dist;
                    best = uuid;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mythicType(Object activeMob) {
        if (activeMob == null) return null;
        try {
            Object type = activeMob.getClass().getMethod("getMobType").invoke(activeMob);
            return type == null ? null : type.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getMythicType(UUID entityUuid) {
        if (entityUuid == null) return null;
        try {
            Class<?> mbClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mbClass.getMethod("inst").invoke(null);
            Object mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object opt = mobManager.getClass().getMethod("getActiveMob", UUID.class).invoke(mobManager, entityUuid);
            if (opt instanceof Optional<?> o && o.isPresent()) return mythicType(o.get());
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isCompletionController(String mobName) {
        if (mobName == null) return false;
        String s = mobName.toLowerCase(java.util.Locale.ROOT);
        return s.contains("level_controller") || s.contains("controller");
    }

    /**
     * 鍦ㄦ寚瀹氫綅缃埛涓€鍙?MythicMob銆傚繀椤诲湪涓荤嚎绋嬭皟鐢ㄣ€?     * @return 鍒峰嚭瀹炰綋鐨?UUID锛涘け璐ヨ繑鍥?null銆傚懡浠ゅ厹搴曡矾寰勬垚鍔熶篃杩斿洖 null锛堟嬁涓嶅埌瀹炰綋寮曠敤锛夈€?     */
    @Deprecated
    public static UUID spawnLegacy(Plugin plugin, String mobName, Location loc, int level) {
        if (mobName == null || mobName.isBlank() || loc == null) return null;
        UUID uuid = spawnViaApi(plugin, mobName, loc, level);
        if (uuid != null) return uuid;
        spawnViaCommand(mobName, loc);
        return null;
    }

    /**
     * 反射调用 io.lumine.mythic.bukkit.MythicBukkit.inst().getAPIHelper().spawnMythicMob(name, loc, level)，
     * 再从返回的 ActiveMob 里反射取出 Bukkit 实体 UUID。
     */
    private static UUID spawnViaApi(Plugin plugin, String mobName, Location loc, int level) {
        if (Boolean.FALSE.equals(apiAvailable)) return null;
        try {
            Class<?> mbClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mbClass.getMethod("inst").invoke(null);
            if (inst == null) return null;
            Object helper = inst.getClass().getMethod("getAPIHelper").invoke(inst);
            if (helper == null) return null;
            Method m = findSpawnMethod(helper.getClass());
            if (m == null) { apiAvailable = false; return null; }
            Class<?> lvlType = m.getParameterTypes()[2];
            Object lvlArg = (lvlType == double.class || lvlType == Double.class) ? (double) level : level;
            Object activeMob = m.invoke(helper, mobName, loc, lvlArg);
            apiAvailable = true;
            return extractBukkitUuid(activeMob);
        } catch (Throwable t) {
            apiAvailable = false;
            return null;
        }
    }

    /** ActiveMob.getEntity().getBukkitEntity().getUniqueId()，全程反射，任一环节失败都返回 null（不影响已刷出的怪）。 */
    private static UUID extractBukkitUuid(Object activeMob) {
        if (activeMob == null) return null;
        try {
            Object uuid = activeMob.getClass().getMethod("getUniqueId").invoke(activeMob);
            if (uuid instanceof UUID u) return u;
        } catch (Throwable ignored) {}
        try {
            Object abstractEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
            if (abstractEntity == null) return null;
            Object bukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity").invoke(abstractEntity);
            if (bukkitEntity instanceof Entity e) return e.getUniqueId();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 不同 MythicMobs 版本 spawnMythicMob 的等级参数有 int / double 两种，逐一探测。 */
    private static Method findSpawnMethod(Class<?> helperClass) {
        for (Class<?> lvl : new Class<?>[]{int.class, double.class}) {
            try {
                return helperClass.getMethod("spawnMythicMob", String.class, Location.class, lvl);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /** 控制台命令兜底：mm mobs spawn <name> 1 <world,x,y,z>。 */
    private static boolean spawnViaCommand(String mobName, Location loc) {
        try {
            String where = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "mm mobs spawn " + mobName + " 1 " + where);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 查某只怪是否还活着——优先调用 MythicMobs MobManager（能正确处理假死/变身等特殊机制），
     * 反射失败（MM 未装/API 变化）时退化为直查 Bukkit 实体存活状态。
     */
    public static boolean isAlive(UUID entityUuid) {
        if (entityUuid == null) return false;
        try {
            Class<?> mbClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mbClass.getMethod("inst").invoke(null);
            Object mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object opt = mobManager.getClass().getMethod("getActiveMob", UUID.class).invoke(mobManager, entityUuid);
            if (opt instanceof Optional<?> o) return o.isPresent();
        } catch (Throwable ignored) {
            // MM API 不可用/反射失败，落到下面的兜底检查
        }
        Entity e = Bukkit.getEntity(entityUuid);
        return e != null && e.isValid() && !e.isDead();
    }
}
