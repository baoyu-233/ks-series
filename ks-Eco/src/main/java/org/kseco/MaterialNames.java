package org.kseco;

import java.util.HashMap;
import java.util.Map;

/** Minecraft 材质英文 → 中文名映射（官方收购常见物品）。 */
public final class MaterialNames {

    private MaterialNames() {}

    private static final Map<String, String> NAMES;
    static {
        Map<String, String> m = new HashMap<>();
        // 农作物
        m.put("WHEAT", "小麦"); m.put("WHEAT_SEEDS", "小麦种子");
        m.put("CARROT", "胡萝卜"); m.put("POTATO", "马铃薯");
        m.put("BEETROOT", "甜菜根"); m.put("BEETROOT_SEEDS", "甜菜根种子");
        m.put("MELON", "西瓜"); m.put("MELON_SLICE", "西瓜片");
        m.put("PUMPKIN", "南瓜"); m.put("SUGAR_CANE", "甘蔗");
        m.put("BAMBOO", "竹子"); m.put("CACTUS", "仙人掌");
        m.put("COCOA_BEANS", "可可豆"); m.put("SWEET_BERRIES", "甜浆果");
        m.put("GLOW_BERRIES", "发光浆果"); m.put("NETHER_WART", "地狱疣");
        // 木材
        m.put("OAK_LOG", "橡木原木"); m.put("SPRUCE_LOG", "云杉原木");
        m.put("BIRCH_LOG", "白桦原木"); m.put("JUNGLE_LOG", "丛林原木");
        m.put("ACACIA_LOG", "金合欢原木"); m.put("DARK_OAK_LOG", "深色橡木原木");
        m.put("MANGROVE_LOG", "红树原木"); m.put("CHERRY_LOG", "樱花原木");
        m.put("OAK_PLANKS", "橡木木板"); m.put("SPRUCE_PLANKS", "云杉木板");
        m.put("BIRCH_PLANKS", "白桦木板");
        // 矿物
        m.put("COAL", "煤炭"); m.put("CHARCOAL", "木炭");
        m.put("IRON_INGOT", "铁锭"); m.put("IRON_ORE", "铁矿石");
        m.put("DEEPSLATE_IRON_ORE", "深板岩铁矿石"); m.put("RAW_IRON", "粗铁");
        m.put("GOLD_INGOT", "金锭"); m.put("GOLD_ORE", "金矿石"); m.put("RAW_GOLD", "粗金");
        m.put("DIAMOND", "钻石"); m.put("DIAMOND_ORE", "钻石矿石");
        m.put("EMERALD", "绿宝石"); m.put("EMERALD_ORE", "绿宝石矿石");
        m.put("LAPIS_LAZULI", "青金石"); m.put("REDSTONE", "红石粉");
        m.put("QUARTZ", "下界石英"); m.put("COPPER_INGOT", "铜锭"); m.put("RAW_COPPER", "粗铜");
        m.put("NETHERITE_INGOT", "下界合金锭"); m.put("NETHERITE_SCRAP", "下界合金碎片");
        m.put("AMETHYST_SHARD", "紫水晶碎片");
        // 石材
        m.put("COBBLESTONE", "圆石"); m.put("STONE", "石头");
        m.put("GRAVEL", "沙砾"); m.put("SAND", "沙子");
        m.put("CLAY", "黏土"); m.put("CLAY_BALL", "黏土球");
        m.put("BRICK", "砖块"); m.put("BRICKS", "砖");
        m.put("NETHER_BRICK", "地狱砖"); m.put("OBSIDIAN", "黑曜石");
        m.put("DEEPSLATE", "深板岩"); m.put("COBBLED_DEEPSLATE", "圆深板岩");
        m.put("BASALT", "玄武岩"); m.put("DIORITE", "闪长岩");
        m.put("GRANITE", "花岗岩"); m.put("ANDESITE", "安山岩");
        m.put("CALCITE", "方解石"); m.put("TUFF", "凝灰岩");
        m.put("DRIPSTONE_BLOCK", "滴水石块"); m.put("POINTED_DRIPSTONE", "尖形滴水石");
        // 生物掉落
        m.put("LEATHER", "皮革"); m.put("BEEF", "生牛肉"); m.put("COOKED_BEEF", "熟牛肉");
        m.put("PORKCHOP", "生猪排"); m.put("COOKED_PORKCHOP", "熟猪排");
        m.put("CHICKEN", "生鸡肉"); m.put("COOKED_CHICKEN", "熟鸡肉");
        m.put("MUTTON", "生羊肉"); m.put("COOKED_MUTTON", "熟羊肉");
        m.put("RABBIT", "生兔肉"); m.put("COOKED_RABBIT", "熟兔肉");
        m.put("COD", "鳕鱼"); m.put("COOKED_COD", "熟鳕鱼");
        m.put("SALMON", "鲑鱼"); m.put("COOKED_SALMON", "熟鲑鱼");
        m.put("EGG", "鸡蛋"); m.put("FEATHER", "羽毛");
        m.put("BONE", "骨头"); m.put("BONE_MEAL", "骨粉");
        m.put("STRING", "线"); m.put("SPIDER_EYE", "蜘蛛眼");
        m.put("ROTTEN_FLESH", "腐肉"); m.put("GUNPOWDER", "火药");
        m.put("SLIME_BALL", "史莱姆球"); m.put("BLAZE_ROD", "烈焰棒");
        m.put("ENDER_PEARL", "末影珍珠"); m.put("ENDER_EYE", "末影之眼");
        m.put("GHAST_TEAR", "恶魂之泪"); m.put("MAGMA_CREAM", "岩浆膏");
        m.put("HONEYCOMB", "蜂巢"); m.put("HONEY_BOTTLE", "蜂蜜瓶");
        m.put("INK_SAC", "墨囊"); m.put("GLOW_INK_SAC", "发光墨囊");
        m.put("RABBIT_HIDE", "兔皮"); m.put("RABBIT_FOOT", "兔子脚");
        m.put("TURTLE_EGG", "海龟蛋"); m.put("SCUTE", "鳞甲");
        m.put("PHANTOM_MEMBRANE", "幻翼膜");
        // 工业/食品/其他
        m.put("SUGAR", "糖"); m.put("PAPER", "纸"); m.put("BOOK", "书");
        m.put("GLASS_BOTTLE", "玻璃瓶"); m.put("GLASS", "玻璃");
        m.put("WOOL", "白色羊毛"); m.put("WHITE_WOOL", "白色羊毛");
        m.put("FLINT", "燧石"); m.put("GLOWSTONE_DUST", "萤石粉"); m.put("GLOWSTONE", "萤石");
        m.put("SOUL_SAND", "灵魂沙"); m.put("SOUL_SOIL", "灵魂土");
        m.put("BLAZE_POWDER", "烈焰粉"); m.put("NETHER_QUARTZ_ORE", "下界石英矿石");
        m.put("MUSHROOM_STEW", "蘑菇煲"); m.put("BREAD", "面包");
        m.put("APPLE", "苹果"); m.put("GOLDEN_APPLE", "金苹果");
        m.put("CHORUS_FRUIT", "紫颂果"); m.put("POPPED_CHORUS_FRUIT", "爆裂紫颂果");
        m.put("PRISMARINE_SHARD", "海晶碎片"); m.put("PRISMARINE_CRYSTALS", "海晶晶体");
        m.put("SEA_LANTERN", "海晶灯"); m.put("SPONGE", "海绵"); m.put("WET_SPONGE", "湿海绵");
        m.put("SEAGRASS", "海草"); m.put("KELP", "海带");
        m.put("DRIED_KELP", "干海带"); m.put("DRIED_KELP_BLOCK", "干海带块");
        NAMES = m;
    }

    /** 获取材质中文名；若无映射则把 SNAKE_CASE 转成首字母大写形式返回。 */
    public static String get(String material) {
        if (material == null) return "未知";
        String upper = material.toUpperCase();
        if (NAMES.containsKey(upper)) return NAMES.get(upper);
        // fallback: 把下划线转空格，首字母大写
        String[] parts = upper.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(p.charAt(0)).append(p.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /** 税种分类 → 中文说明。 */
    public static String getTaxCategoryName(String category) {
        return switch (category == null ? "" : category) {
            case "MARKET_TRADE"      -> "玩家市场交易税";
            case "PROPERTY_TRADE"    -> "房屋买卖契税";
            case "OFFICIAL_TRADE"    -> "官方收购税";
            case "ENTERPRISE_SMALL"  -> "小型企业所得税";
            case "ENTERPRISE_MEDIUM" -> "中型企业所得税";
            case "ENTERPRISE_LARGE"  -> "大型企业所得税";
            case "ENTERPRISE_TAX"    -> "企业综合税";
            case "DIVIDEND_TAX"      -> "股息分红税";
            case "BANK_INTEREST"     -> "银行利息税";
            case "PLAYER_TRANSFER"   -> "玩家转账税";
            case "TAX_PENALTY"       -> "税收滞纳金率";
            case "INCOME_TAX"        -> "个人所得税";
            case "PROPERTY_HOLD_TAX" -> "房产持有税";
            case "CAPITAL_GAINS"     -> "资本利得税";
            default                  -> category;
        };
    }
}
