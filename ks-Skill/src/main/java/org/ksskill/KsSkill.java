package org.ksskill;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.ksskill.listener.DamageListener;
import org.ksskill.listener.KillListener;
import org.ksskill.listener.PlayerStateListener;
import org.ksskill.listener.SneakListener;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;

/**
 * ks-Skill —— 玩家被动技能触发引擎。
 *
 * <p>补上 MythicMobs 原生缺失的“玩家侧被动触发”层：触发器 + 条件 + 概率 + 冷却 + 多来源绑定，
 * 效果直接委托 MythicMobs metaskill。技能条目全在 skills.yml，四种绑定来源
 * （称号/权限/物品/指令授予）满足任一即生效。</p>
 */
public final class KsSkill extends JavaPlugin {

    private SkillRegistry registry;
    private GrantStore grants;
    private TitleBuffCache titleCache;
    private CooldownTracker cooldown;
    private BindingResolver binding;
    private SkillEngine engine;

    @Override
    public void onEnable() {
        saveResourceIfAbsent("skills.yml");
        deployMythicSkills();

        registry = new SkillRegistry();
        registry.load(new File(getDataFolder(), "skills.yml"), getLogger());

        grants = new GrantStore(getDataFolder(), getLogger());
        grants.init();
        titleCache = new TitleBuffCache();
        cooldown = new CooldownTracker();
        binding = new BindingResolver(this, titleCache, grants);
        engine = new SkillEngine(registry, binding, cooldown);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new DamageListener(engine), this);
        pm.registerEvents(new KillListener(engine), this);
        pm.registerEvents(new SneakListener(engine), this);
        pm.registerEvents(new PlayerStateListener(cooldown, titleCache), this);

        // ON_INTERVAL 常驻扫描：每秒一次（无 interval 技能时即时返回）
        getServer().getScheduler().runTaskTimer(this,
                new IntervalTask(registry, binding, cooldown), 40L, 20L);

        Objects.requireNonNull(getCommand("ksskill")).setExecutor(new SkillCommand(this));

        getLogger().info("ks-Skill v" + getDescription().getVersion()
                + " 已启用 (MythicMobs=" + MythicCaster.isAvailable() + ")");
    }

    @Override
    public void onDisable() {
        if (grants != null) grants.close();
        getLogger().info("ks-Skill 已禁用");
    }

    // ==================== 访问器（供指令用） ====================

    public SkillRegistry registry() { return registry; }
    public GrantStore grants() { return grants; }
    public BindingResolver binding() { return binding; }
    public SkillEngine engine() { return engine; }

    public void reloadSkills() {
        registry.load(new File(getDataFolder(), "skills.yml"), getLogger());
    }

    // ==================== 资源释放 ====================

    private void saveResourceIfAbsent(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
    }

    /**
     * 把默认 MythicMobs 技能释放到 plugins/MythicMobs/Skills/（仅当目标不存在，不覆盖管理员修改）。
     * softdepend 保证本插件在 MythicMobs 之后加载，故此时 Skills 目录已存在；释放后 mm reload skills 让其立即可用。
     */
    private void deployMythicSkills() {
        try {
            File mmSkills = new File(getDataFolder().getParentFile(), "MythicMobs/Skills");
            if (!mmSkills.isDirectory()) {
                getLogger().info("未检测到 MythicMobs/Skills 目录，跳过默认技能释放");
                return;
            }
            File dest = new File(mmSkills, "ks_skill_defaults.yml");
            if (dest.exists()) return;
            try (InputStream in = getResource("mythicskills/ks_skill_defaults.yml")) {
                if (in == null) return;
                Files.copy(in, dest.toPath());
            }
            getLogger().info("已释放默认技能 → MythicMobs/Skills/ks_skill_defaults.yml");
            if (MythicCaster.isAvailable()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm reload skills");
            }
        } catch (Exception e) {
            getLogger().warning("释放默认 MythicMobs 技能失败: " + e.getMessage());
        }
    }
}
