package org.kseco.extra.realestate;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * Material → 近似 RGB 颜色（24bit int，0xRRGGBB），用于网页 3D 体素查看器渲染房屋方块。
 * 自包含实现（不依赖 ksHWP 的 MapRenderer.COLOR_MAP），因为 extra 模块按架构只依赖 ks-core，不应跨依赖 ksHWP。
 * 覆盖常见建筑材质，未命中时按材质名关键字归类，最后兜底为中灰色。
 */
final class VoxelColors {

    private VoxelColors() {}

    private static final Map<Material, Integer> MAP = new HashMap<>();
    private static final Map<String, Integer> DYE_COLORS = Map.ofEntries(
            Map.entry("WHITE", 0xE9ECEC), Map.entry("LIGHT_GRAY", 0x9D9D97),
            Map.entry("GRAY", 0x474F52), Map.entry("BLACK", 0x1D1D21),
            Map.entry("BROWN", 0x835432), Map.entry("RED", 0xB02E26),
            Map.entry("ORANGE", 0xF9801D), Map.entry("YELLOW", 0xFED83D),
            Map.entry("LIME", 0x80C71F), Map.entry("GREEN", 0x5E7C16),
            Map.entry("CYAN", 0x169C9C), Map.entry("LIGHT_BLUE", 0x3AB3DA),
            Map.entry("BLUE", 0x3C44AA), Map.entry("PURPLE", 0x8932B8),
            Map.entry("MAGENTA", 0xC74EBD), Map.entry("PINK", 0xF38BAA));

    static {
        put(Material.STONE, 0x7f7f7f);
        put(Material.COBBLESTONE, 0x6b6b6b);
        put(Material.MOSSY_COBBLESTONE, 0x5e6b5e);
        put(Material.STONE_BRICKS, 0x8a8a8a);
        put(Material.SMOOTH_STONE, 0xa3a3a3);
        put(Material.BRICKS, 0x9c5b4a);
        put(Material.DIRT, 0x8a5a32);
        put(Material.GRASS_BLOCK, 0x5d9c3f);
        put(Material.SAND, 0xdcd089);
        put(Material.SANDSTONE, 0xd8cd96);
        put(Material.RED_SAND, 0xb5562b);
        put(Material.GRAVEL, 0x8d8a86);
        put(Material.GLASS, 0xbfe8f0);
        put(Material.OAK_PLANKS, 0xb08a52);
        put(Material.SPRUCE_PLANKS, 0x7a5a36);
        put(Material.BIRCH_PLANKS, 0xd7c489);
        put(Material.JUNGLE_PLANKS, 0xa9784f);
        put(Material.ACACIA_PLANKS, 0xb5562b);
        put(Material.DARK_OAK_PLANKS, 0x4f3a23);
        put(Material.CRIMSON_PLANKS, 0x7a3b4a);
        put(Material.WARPED_PLANKS, 0x288e85);
        put(Material.CHERRY_PLANKS, 0xE4B4B0);
        put(Material.BAMBOO_PLANKS, 0xC6B35B);
        put(Material.OAK_LOG, 0x6e5733);
        put(Material.SPRUCE_LOG, 0x3f2e1c);
        put(Material.OAK_DOOR, 0xb08a52);
        put(Material.IRON_BLOCK, 0xd8d8d8);
        put(Material.GOLD_BLOCK, 0xf2cf45);
        put(Material.DIAMOND_BLOCK, 0x6be3d8);
        put(Material.EMERALD_BLOCK, 0x3fbf5e);
        put(Material.NETHERRACK, 0x5a2a2a);
        put(Material.OBSIDIAN, 0x14101c);
        put(Material.WATER, 0x3a6fd1);
        put(Material.LAVA, 0xd1551f);
        put(Material.SNOW, 0xf3f8fc);
        put(Material.SNOW_BLOCK, 0xf3f8fc);
        put(Material.ICE, 0xa8d3e6);
        put(Material.BOOKSHELF, 0xa9784f);
        put(Material.CRAFTING_TABLE, 0x8a5a32);
        put(Material.FURNACE, 0x7f7f7f);
        put(Material.TERRACOTTA, 0x9c5b4a);
        put(Material.WHITE_WOOL, 0xeaeaea);
        put(Material.RED_WOOL, 0xa13a2e);
        put(Material.BLUE_WOOL, 0x35399d);
        put(Material.BLACK_WOOL, 0x1a1a1e);
    }

    private static void put(Material m, int rgb) { MAP.put(m, rgb); }

    /** 返回材质对应的 24bit RGB 颜色；未在表里的按名称关键字归类，最后兜底中灰色。 */
    static int colorOf(Material mat) {
        Integer exact = MAP.get(mat);
        if (exact != null) return exact;
        String n = mat.name();
        if (n.endsWith("_WOOL") || n.endsWith("_CONCRETE") || n.endsWith("_TERRACOTTA")
                || n.endsWith("_GLASS") || n.endsWith("_GLASS_PANE")) {
            for (Map.Entry<String, Integer> dye : DYE_COLORS.entrySet()) {
                if (n.startsWith(dye.getKey() + "_")) return dye.getValue();
            }
            return 0xb0b0b0;
        }
        if (n.contains("CHERRY")) return 0xE4B4B0;
        if (n.contains("QUARTZ") || n.contains("CALCITE")) return 0xE6E2DA;
        if (n.contains("DEEPSLATE")) return 0x3C3C42;
        if (n.contains("SEA_LANTERN")) return 0xB7E5D8;
        if (n.contains("LOG") || n.contains("WOOD")) return 0x6e5733;
        if (n.contains("PLANKS")) return 0xb08a52;
        if (n.contains("LEAVES")) return 0x4a7a3a;
        if (n.contains("ORE")) return 0x9a9a9a;
        if (n.contains("STONE") || n.contains("ANDESITE") || n.contains("DIORITE") || n.contains("GRANITE")) {
            return 0x8a8a8a;
        }
        if (n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("FENCE") || n.contains("STAIRS")
                || n.contains("SLAB") || n.contains("WALL")) {
            return 0x9a8060;
        }
        return 0x808080;
    }
}
