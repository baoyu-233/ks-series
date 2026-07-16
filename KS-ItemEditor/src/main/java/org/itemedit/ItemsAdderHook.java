package org.itemedit;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有对 ItemsAdder 的直接引用都隔离在这个类里。
 * 只有在 ItemEditor#itemsAdderEnabled() 为 true 时才调用这里的方法，
 * 否则会触发 NoClassDefFoundError。
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {}

    /** 根据 namespace:id 取得 IA 物品的副本，未注册返回 null。 */
    public static ItemStack reference(String namespacedId) {
        CustomStack cs = CustomStack.getInstance(namespacedId);
        return cs == null ? null : cs.getItemStack();
    }

    /** 列出所有已注册 IA 物品的 namespace:id，按字母排序。 */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>();
        for (CustomStack cs : ItemsAdder.getAllItems()) {
            ids.add(cs.getNamespace() + ":" + cs.getId());
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }
}
