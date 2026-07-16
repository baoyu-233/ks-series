package org.itemedit;

import java.util.Collections;
import java.util.List;

/**
 * FE 附魔反射提取数据 —— 避免编译期硬依赖 FotiaEnchantment API。
 * 每个实例对应一个已启用的 FE 附魔。
 */
public class FeEnchantData {
    private final String id;
    private final int maxLevel;
    private final String category;
    private final List<String> conflicts;

    public FeEnchantData(String id, int maxLevel, String category, List<String> conflicts) {
        this.id = id;
        this.maxLevel = maxLevel;
        this.category = category != null ? category : "unknown";
        this.conflicts = conflicts != null ? conflicts : Collections.emptyList();
    }

    public String getId() { return id; }
    public int getMaxLevel() { return maxLevel; }
    public String getCategory() { return category; }
    public List<String> getConflicts() { return conflicts; }
}
