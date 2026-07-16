package org.kseco.extra;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.kseco.KsEco;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * ks-Eco Extra 子模块加载器。
 *
 * 从 plugins/ks-Eco/extra/ 目录扫描并加载 JAR 模块。
 * 每个模块必须实现 KsEcoExtraModule 接口。
 *
 * 加载流程：
 * 1. 扫描 extra/ 目录下的 .jar 文件
 * 2. 检查 META-INF/ks-eco-extra.properties 获取主类名
 * 3. 加载并实例化模块
 * 4. 依次调用 onLoad() → onEnable()
 */
public final class ExtraModuleLoader {

    private final KsEco plugin;
    private final Map<String, LoadedModule> loadedModules = new ConcurrentHashMap<>();
    private final Set<String> enabledModuleIds = ConcurrentHashMap.newKeySet();

    public ExtraModuleLoader(KsEco plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载所有模块。
     */
    public void loadModules() {
        if (!plugin.ecoConfig().isExtraEnabled()) {
            plugin.getLogger().info("Extra 模块加载已禁用。");
            return;
        }

        File extraDir = new File(plugin.getDataFolder(), plugin.ecoConfig().getExtraDirectory());
        if (!extraDir.exists()) {
            extraDir.mkdirs();
            plugin.getLogger().info("Extra 模块目录已创建: " + extraDir.getPath());
            return;
        }

        File[] jars = extraDir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            plugin.getLogger().info("Extra 模块目录为空。");
            return;
        }

        for (File jar : jars) {
            loadModule(jar);
        }

        // 触发每个模块的 onEnable（init 建表等）
        enableAll();

        plugin.getLogger().info("Extra 模块加载完成。已加载: " + loadedModules.size() + " 个");
    }

    private void loadModule(File jarFile) {
        // Strip version suffix from JAR name: "ks-Eco-bank-1.1.0.jar" → "ks-eco-bank"
        String raw = jarFile.getName().replace(".jar", "");
        String moduleId = raw.replaceAll("-\\d+(\\.\\d+)*$", "").toLowerCase();
        List<String> configuredModules = plugin.ecoConfig().getEnabledModules();

        // 检查是否在启用列表中
        if (!configuredModules.isEmpty() && !configuredModules.contains(moduleId)) {
            plugin.getLogger().info("模块 " + moduleId + " 未在配置中启用，跳过。");
            return;
        }

        try (JarFile jar = new JarFile(jarFile)) {
            // 读取模块描述符
            var entry = jar.getJarEntry("META-INF/ks-eco-extra.properties");
            if (entry == null) {
                plugin.getLogger().warning("模块 " + moduleId + " 缺少 META-INF/ks-eco-extra.properties，跳过。");
                return;
            }

            Properties props = new Properties();
            props.load(jar.getInputStream(entry));
            String mainClass = props.getProperty("main-class");
            if (mainClass == null || mainClass.isEmpty()) {
                plugin.getLogger().warning("模块 " + moduleId + " 未指定 main-class。");
                return;
            }

            // 加载类
            URL url = jarFile.toURI().toURL();
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{url},
                    plugin.getClass().getClassLoader()
            );

            Class<?> clazz = Class.forName(mainClass, true, classLoader);
            if (!KsEcoExtraModule.class.isAssignableFrom(clazz)) {
                plugin.getLogger().warning("模块 " + moduleId + " 的主类未实现 KsEcoExtraModule 接口。");
                classLoader.close();
                return;
            }

            KsEcoExtraModule module = (KsEcoExtraModule) clazz.getDeclaredConstructor().newInstance();
            module.onLoad(plugin);

            loadedModules.put(moduleId, new LoadedModule(moduleId, module, classLoader));
            plugin.getLogger().info("模块已加载: " + module.getName() + " (" + moduleId + ")");

        } catch (Exception e) {
            plugin.getLogger().warning("加载模块 " + moduleId + " 失败: " + e.getMessage());
        }
    }

    /**
     * 启用所有已加载模块。
     */
    public void enableAll() {
        for (LoadedModule lm : List.copyOf(loadedModules.values())) {
            try {
                lm.module.onEnable();
                enabledModuleIds.add(lm.id);
                plugin.getLogger().info("模块已启用: " + lm.module.getName());
            } catch (Exception e) {
                plugin.getLogger().warning("启用模块 " + lm.id + " 失败: " + e.getMessage());
                loadedModules.remove(lm.id, lm);
                unregisterModuleListeners(lm.classLoader);
                try { lm.classLoader.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 停用所有模块。
     */
    public void disableAll() {
        for (LoadedModule lm : loadedModules.values()) {
            enabledModuleIds.remove(lm.id);
            try {
                lm.module.onDisable();
                unregisterModuleListeners(lm.classLoader);
                lm.classLoader.close();
            } catch (Exception e) {
                plugin.getLogger().warning("停用模块 " + lm.id + " 异常: " + e.getMessage());
            }
        }
        loadedModules.clear();
        enabledModuleIds.clear();
    }

    /** Reload only ks-Eco Extra modules. Must be called from the server thread. */
    public void reloadModules() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Extra modules must be reloaded on the server thread");
        }
        disableAll();
        loadModules();
    }

    /** Remove only listeners loaded by this Extra JAR, never listeners from ks-Eco itself. */
    private void unregisterModuleListeners(ClassLoader classLoader) {
        for (HandlerList handlerList : HandlerList.getHandlerLists()) {
            for (RegisteredListener registration : handlerList.getRegisteredListeners()) {
                Listener listener = registration.getListener();
                if (registration.getPlugin().equals(plugin)
                        && listener.getClass().getClassLoader() == classLoader) {
                    handlerList.unregister(listener);
                }
            }
        }
    }

    /**
     * 已加载模块数量。
     */
    public int loadedModuleCount() {
        return enabledModuleIds.size();
    }

    /**
     * 获取已加载的模块 ID 列表。
     */
    public Set<String> getLoadedModuleIds() {
        return Set.copyOf(enabledModuleIds);
    }

    /**
     * 根据 ID 获取已加载的模块实例。
     * @return 模块实例，未找到返回 null
     */
    /**
     * 根据 ID 前缀匹配已加载的模块实例。
     * 例如 "ks-eco-bank" 匹配 "ks-Eco-bank-1.0.0"。
     * @return 模块实例，未找到返回 null
     */
    public KsEcoExtraModule getModule(String id) {
        // 先精确匹配
        LoadedModule lm = loadedModules.get(id);
        if (lm != null && enabledModuleIds.contains(lm.id)) return lm.module;
        // 前缀匹配（忽略大小写）
        String prefix = id.toLowerCase();
        for (var entry : loadedModules.entrySet()) {
            if (enabledModuleIds.contains(entry.getKey())
                    && entry.getKey().toLowerCase().startsWith(prefix)) {
                return entry.getValue().module;
            }
        }
        return null;
    }

    // ---- 内部类 ----

    private record LoadedModule(String id, KsEcoExtraModule module, URLClassLoader classLoader) {}
}
