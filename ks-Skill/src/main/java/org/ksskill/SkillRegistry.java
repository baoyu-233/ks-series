package org.ksskill;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.ksskill.model.SkillDef;
import org.ksskill.model.TriggerType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 解析 skills.yml，把技能条目按触发器分组索引，供各监听器高频读取。
 * {@link #load} 可重复调用实现热重载。
 */
public final class SkillRegistry {

    private final Map<TriggerType, List<SkillDef>> byTrigger = new EnumMap<>(TriggerType.class);
    private final Map<String, SkillDef> byId = new HashMap<>();

    public synchronized void load(File file, Logger log) {
        byTrigger.clear();
        byId.clear();
        for (TriggerType t : TriggerType.values()) byTrigger.put(t, new ArrayList<>());

        if (!file.exists()) {
            log.warning("skills.yml 不存在，未加载任何技能");
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection skills = yml.getConfigurationSection("skills");
        if (skills == null) {
            log.warning("skills.yml 缺少 skills 段，未加载任何技能");
            return;
        }

        int ok = 0;
        for (String id : skills.getKeys(false)) {
            ConfigurationSection s = skills.getConfigurationSection(id);
            if (s == null) continue;

            TriggerType trigger = TriggerType.fromConfig(s.getString("trigger"));
            if (trigger == null) {
                log.warning("技能 " + id + " 的 trigger 非法，跳过");
                continue;
            }
            String mythic = s.getString("mythic-skill", "");
            if (mythic == null || mythic.isBlank()) {
                log.warning("技能 " + id + " 未配置 mythic-skill，跳过");
                continue;
            }

            double chance = s.getDouble("chance", 1.0);
            long cd = s.getLong("cooldown", 0);
            long interval = s.getLong("interval-seconds", 2);
            String display = s.getString("display", id);

            Set<String> causes = new HashSet<>();
            ConfigurationSection cond = s.getConfigurationSection("conditions");
            if (cond != null) {
                for (String c : cond.getStringList("damage-causes")) {
                    causes.add(c.toUpperCase(Locale.ROOT));
                }
            }

            Set<Integer> titles = new HashSet<>();
            List<String> perms = new ArrayList<>();
            Set<String> items = new HashSet<>();
            ConfigurationSection b = s.getConfigurationSection("bindings");
            if (b != null) {
                titles.addAll(b.getIntegerList("titles"));
                perms.addAll(b.getStringList("permissions"));
                items.addAll(b.getStringList("items"));
            }

            Set<String> worlds = new HashSet<>(s.getStringList("worlds"));

            SkillDef def = new SkillDef(id, display, trigger, chance, cd, interval, mythic,
                    causes, titles, perms, items, worlds);
            byId.put(id, def);
            byTrigger.get(trigger).add(def);
            ok++;
        }
        log.info("已加载 " + ok + " 个技能条目");
    }

    /** 某触发器下的技能列表（永不为 null；未加载时为空）。 */
    public List<SkillDef> byTrigger(TriggerType t) {
        List<SkillDef> list = byTrigger.get(t);
        return list == null ? List.of() : list;
    }

    public SkillDef get(String id) {
        return byId.get(id);
    }

    public Collection<SkillDef> all() {
        return new ArrayList<>(byId.values());
    }
}
