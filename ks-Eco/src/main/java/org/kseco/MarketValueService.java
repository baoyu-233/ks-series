package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Keeps official buy valuation separate from the anti-transfer market floor. */
public final class MarketValueService {
    private static final int MAX_RECIPE_DEPTH = 12;
    private static final Map<Material, Map<Material, Integer>> CRAFTING_FLOORS = new LinkedHashMap<>();
    static {
        addDiamondTool(Material.DIAMOND_SWORD, 2); addDiamondTool(Material.DIAMOND_PICKAXE, 3);
        addDiamondTool(Material.DIAMOND_AXE, 3); addDiamondTool(Material.DIAMOND_SHOVEL, 1);
        addDiamondTool(Material.DIAMOND_HOE, 2);
        addDiamondTool(Material.DIAMOND_HELMET, 5); addDiamondTool(Material.DIAMOND_CHESTPLATE, 8);
        addDiamondTool(Material.DIAMOND_LEGGINGS, 7); addDiamondTool(Material.DIAMOND_BOOTS, 4);
    }

    private final KsEco plugin;
    private volatile RecipeGraph recipeGraph = RecipeGraph.EMPTY;
    private volatile Object fePdcManager;
    private volatile Method feGetEnchantments;
    private volatile boolean feLookupAttempted;

    public MarketValueService(KsEco plugin) {
        this.plugin = plugin;
        refreshRecipeSnapshot();
    }

    /**
     * Rebuilds the immutable recipe graph. This is the only code path that reads
     * Bukkit's recipe registry and must run on the server thread.
     */
    public void refreshRecipeSnapshot() {
        requirePrimaryThread("refresh the recipe snapshot");
        Map<Material, List<RecipeSnapshot>> recipesByResult = new EnumMap<>(Material.class);
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        int recipeCount = 0;
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            try {
                ItemStack result = recipe.getResult();
                if (result == null || result.getType().isAir()) continue;
                List<IngredientSnapshot> ingredients = snapshotRecipeIngredients(recipe);
                if (ingredients.isEmpty()) continue;
                recipesByResult.computeIfAbsent(result.getType(), ignored -> new ArrayList<>())
                        .add(new RecipeSnapshot(Math.max(1, result.getAmount()), ingredients));
                recipeCount++;
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Skipping recipe during market snapshot: " + ex.getMessage());
            }
        }

        Map<Material, List<RecipeSnapshot>> immutable = new EnumMap<>(Material.class);
        recipesByResult.forEach((material, materialRecipes) ->
                immutable.put(material, List.copyOf(materialRecipes)));
        recipeGraph = new RecipeGraph(Map.copyOf(immutable), recipeCount);
        plugin.getLogger().info("Market recipe snapshot refreshed: " + recipeCount + " recipes.");
    }

    /** Player-initiated official purchases exclude all enchantment premiums. */
    public double officialBuyValue(ItemStack item) {
        PreparedItem prepared = prepareMarketItem(item);
        return value(prepared, false, 0, captureInputs(false), new ValuationContext(recipeDepthLimit()));
    }

    /** Used by listing floors and official market sweep; includes vanilla and FE enchantments. */
    public double marketFloorValue(ItemStack item) {
        return newMarketFloorSession().value(prepareMarketItem(item));
    }

    public double marketFloorUnitValue(ItemStack item) {
        return newMarketFloorSession().unitValue(prepareMarketItem(item));
    }

    /**
     * Converts all Bukkit-backed item state into a deeply immutable value object.
     * Call on the server thread, then pass the result to a worker.
     */
    public PreparedItem prepareMarketItem(ItemStack item) {
        requirePrimaryThread("prepare a market item");
        return prepareItem(item, 0, Math.max(1, plugin.ecoConfig().getMaxRecursionDepth()));
    }

    /**
     * Captures prices, valuation settings and the current recipe graph for a bounded
     * batch. The returned session and PreparedItem inputs are safe for worker threads.
     */
    public MarketFloorSession newMarketFloorSession() {
        return new MarketFloorSession(captureInputs(true), recipeDepthLimit());
    }

    private PreparedItem prepareItem(ItemStack item, int depth, int maxDepth) {
        if (item == null || item.getType().isAir()) return PreparedItem.EMPTY;
        int amount = Math.max(1, item.getAmount());
        boolean shulker = ShulkerBoxParser.isShulkerBox(item);
        List<PreparedItem> contents = List.of();
        if (shulker && depth < maxDepth && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
            List<PreparedItem> preparedContents = new ArrayList<>();
            for (ItemStack content : box.getInventory().getContents()) {
                PreparedItem prepared = prepareItem(content, depth + 1, maxDepth);
                if (!prepared.isEmpty()) preparedContents.add(prepared);
            }
            contents = List.copyOf(preparedContents);
        }
        return new PreparedItem(item.getType(), amount, shulker, contents, enchantmentPremium(item));
    }

    private ValuationInputs captureInputs(boolean includeInternalFallback) {
        Map<Material, Double> directValues = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            directValues.put(material, finiteNonNegative(materialValue(material, includeInternalFallback)));
        }
        return new ValuationInputs(
                Map.copyOf(directValues),
                recipeGraph,
                finiteNonNegative(plugin.ecoConfig().getEmptyBoxValue()),
                Math.max(1, plugin.ecoConfig().getMaxRecursionDepth()));
    }

    private double value(PreparedItem item, boolean includeEnchantments, int depth,
                         ValuationInputs inputs, ValuationContext context) {
        if (item == null || item.isEmpty()) return 0.0;
        int amount = Math.max(1, item.amount());
        if (item.shulker()) {
            double total = includeEnchantments ? inputs.emptyBoxValue() * amount : 0.0;
            if (depth >= inputs.maxItemDepth()) return finiteNonNegative(total);
            for (PreparedItem content : item.contents()) {
                total += value(content, includeEnchantments, depth + 1, inputs, context) * amount;
                if (!Double.isFinite(total)) return 0.0;
            }
            return finiteNonNegative(total);
        }

        double unit = directValue(item.material(), inputs);
        if (includeEnchantments) {
            unit = Math.max(unit, recipeFloor(item.material(), inputs, context));
            unit += item.enchantmentPremium();
        }
        return finiteNonNegative(unit * amount);
    }

    private double materialValue(Material material, boolean allowInternalFallback) {
        double configured = plugin.priceEngine().getOfficialBuyPrice(material.name());
        if (configured > 0 || !allowInternalFallback) return configured;
        if (plugin.ecoConfig().hasInternalReferencePrice(material.name())) {
            return plugin.ecoConfig().getInternalReferencePrice(material.name());
        }
        return derivedInternalValue(material);
    }

    /** Base materials live in config; recipe variants are derived from those entries. */
    private double derivedInternalValue(Material material) {
        String name = material.name();
        if (name.endsWith("_STAIRS")) return relatedValue(name, "_STAIRS", 0.75);
        if (name.endsWith("_SLAB")) return relatedValue(name, "_SLAB", 0.50);
        if (name.endsWith("_WALL")) return relatedValue(name, "_WALL", 0.75);
        if (name.endsWith("_FENCE_GATE")) return relatedValue(name, "_FENCE_GATE", 1.25);
        if (name.endsWith("_FENCE")) return relatedValue(name, "_FENCE", 0.90);
        if (name.endsWith("_TRAPDOOR")) return relatedValue(name, "_TRAPDOOR", 0.75);
        if (name.endsWith("_DOOR")) return relatedValue(name, "_DOOR", 1.00);
        if (name.endsWith("_HANGING_SIGN")) return relatedValue(name, "_HANGING_SIGN", 0.90);
        if (name.endsWith("_SIGN")) return relatedValue(name, "_SIGN", 0.75);
        if (name.endsWith("_PRESSURE_PLATE")) return relatedValue(name, "_PRESSURE_PLATE", 0.50);
        if (name.endsWith("_BUTTON")) return relatedValue(name, "_BUTTON", 0.25);
        if (name.endsWith("_GLASS_PANE")) return relatedValue(name, "_PANE", 0.50);
        if (name.endsWith("_CONCRETE_POWDER")) return relatedValue(name, "_POWDER", 0.85);
        if (name.endsWith("_GLAZED_TERRACOTTA")) return relatedValue(name, "_GLAZED_TERRACOTTA", 1.10);
        if (name.endsWith("_LEAVES")) return plugin.ecoConfig().getInternalFallbackUnitValue() * 0.5;
        return plugin.ecoConfig().getInternalFallbackUnitValue();
    }

    private double relatedValue(String name, String suffix, double multiplier) {
        String baseName = name.substring(0, name.length() - suffix.length());
        Material base = Material.matchMaterial(baseName);
        if (base == null) return plugin.ecoConfig().getInternalFallbackUnitValue();
        return materialValue(base, true) * multiplier;
    }

    private double recipeFloor(Material material, ValuationInputs inputs, ValuationContext context) {
        Map<Material, Integer> ingredients = CRAFTING_FLOORS.get(material);
        double total = 0.0;
        if (ingredients != null) {
            for (Map.Entry<Material, Integer> ingredient : ingredients.entrySet()) {
                total += directValue(ingredient.getKey(), inputs) * ingredient.getValue();
            }
        }
        total = Math.max(total, genericEquipmentFloor(material, inputs));
        return Math.max(total, registeredRecipeFloor(material, inputs, context));
    }

    private double registeredRecipeFloor(Material material, ValuationInputs inputs, ValuationContext context) {
        Double cached = context.recipeCache.get(material);
        if (cached != null) return cached;
        if (context.recipePath.size() >= context.maxRecipeDepth || !context.recipePath.add(material)) {
            return directValue(material, inputs);
        }

        double lowest = Double.MAX_VALUE;
        try {
            for (RecipeSnapshot recipe : inputs.recipeGraph().recipesByResult()
                    .getOrDefault(material, List.of())) {
                double total = 0.0;
                boolean valid = true;
                for (IngredientSnapshot ingredient : recipe.ingredients()) {
                    double ingredientValue = choiceValue(ingredient, inputs, context);
                    if (ingredientValue <= 0.0 || !Double.isFinite(ingredientValue)) {
                        valid = false;
                        break;
                    }
                    total += ingredientValue;
                    if (!Double.isFinite(total)) {
                        valid = false;
                        break;
                    }
                }
                if (valid) lowest = Math.min(lowest, total / recipe.outputAmount());
            }
        } finally {
            context.recipePath.remove(material);
        }
        double result = lowest == Double.MAX_VALUE ? 0.0 : finiteNonNegative(lowest);
        context.recipeCache.putIfAbsent(material, result);
        return result;
    }

    private List<IngredientSnapshot> snapshotRecipeIngredients(Recipe recipe) {
        List<RecipeChoice> choices;
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choiceMap = shaped.getChoiceMap();
            choices = new ArrayList<>();
            for (String row : shaped.getShape()) {
                for (char symbol : row.toCharArray()) {
                    RecipeChoice choice = choiceMap.get(symbol);
                    if (choice != null) choices.add(choice);
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            choices = shapeless.getChoiceList();
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            choices = List.of(cooking.getInputChoice());
        } else {
            return List.of();
        }

        List<IngredientSnapshot> ingredients = new ArrayList<>();
        for (RecipeChoice choice : choices) {
            IngredientSnapshot ingredient = snapshotChoice(choice);
            if (!ingredient.alternatives().isEmpty()) ingredients.add(ingredient);
        }
        return List.copyOf(ingredients);
    }

    private IngredientSnapshot snapshotChoice(RecipeChoice choice) {
        if (choice == null) return IngredientSnapshot.EMPTY;
        List<PreparedItem> alternatives = new ArrayList<>();
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            for (Material material : materials.getChoices()) {
                alternatives.add(PreparedItem.basic(material));
            }
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            for (ItemStack item : exact.getChoices()) {
                PreparedItem prepared = prepareItem(item, 0,
                        Math.max(1, plugin.ecoConfig().getMaxRecursionDepth()));
                if (!prepared.isEmpty()) alternatives.add(prepared.withAmount(1));
            }
        } else {
            ItemStack item = choice.getItemStack();
            PreparedItem prepared = prepareItem(item, 0,
                    Math.max(1, plugin.ecoConfig().getMaxRecursionDepth()));
            if (!prepared.isEmpty()) alternatives.add(prepared.withAmount(1));
        }
        return new IngredientSnapshot(alternatives);
    }

    private double choiceValue(IngredientSnapshot choice, ValuationInputs inputs, ValuationContext context) {
        double lowest = Double.MAX_VALUE;
        for (PreparedItem alternative : choice.alternatives()) {
            double candidate;
            if (alternative.hasItemSpecificValue()) {
                candidate = value(alternative, true, 0, inputs, context);
            } else {
                // MaterialChoice has no item metadata, so its direct economic value is sufficient.
                candidate = directValue(alternative.material(), inputs);
            }
            if (candidate > 0.0 && Double.isFinite(candidate)) lowest = Math.min(lowest, candidate);
        }
        return lowest == Double.MAX_VALUE ? 0.0 : lowest;
    }

    private int recipeDepthLimit() {
        return Math.max(1, Math.min(MAX_RECIPE_DEPTH, plugin.ecoConfig().getMaxRecursionDepth()));
    }

    private static double directValue(Material material, ValuationInputs inputs) {
        return inputs.directValues().getOrDefault(material, 0.0);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private void requirePrimaryThread(String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("MarketValueService must " + operation + " on the server thread");
        }
    }

    public final class MarketFloorSession {
        private final ValuationInputs inputs;
        private final int maxRecipeDepth;
        private final ConcurrentMap<Material, Double> recipeCache = new ConcurrentHashMap<>();

        private MarketFloorSession(ValuationInputs inputs, int maxRecipeDepth) {
            this.inputs = inputs;
            this.maxRecipeDepth = maxRecipeDepth;
        }

        /** Pure worker-safe valuation. The input must have been prepared on the server thread. */
        public double unitValue(PreparedItem item) {
            if (item == null || item.isEmpty()) return 0.0;
            return MarketValueService.this.value(item.withAmount(1), true, 0, inputs,
                    new ValuationContext(maxRecipeDepth, recipeCache));
        }

        /** Pure worker-safe valuation which preserves the prepared stack amount. */
        public double value(PreparedItem item) {
            return MarketValueService.this.value(item, true, 0, inputs,
                    new ValuationContext(maxRecipeDepth, recipeCache));
        }

        /** Compatibility path for main-thread callers. Worker code must use PreparedItem. */
        public double unitValue(ItemStack item) {
            return unitValue(prepareMarketItem(item));
        }
    }

    public record PreparedItem(Material material, int amount, boolean shulker,
                               List<PreparedItem> contents, double enchantmentPremium) {
        private static final PreparedItem EMPTY =
                new PreparedItem(Material.AIR, 0, false, List.of(), 0.0);

        public PreparedItem {
            material = material == null ? Material.AIR : material;
            amount = Math.max(0, amount);
            contents = contents == null ? List.of() : List.copyOf(contents);
            enchantmentPremium = finiteNonNegative(enchantmentPremium);
        }

        private static PreparedItem basic(Material material) {
            return new PreparedItem(material, 1, false, List.of(), 0.0);
        }

        public boolean isEmpty() {
            return amount <= 0 || material.isAir();
        }

        private boolean hasItemSpecificValue() {
            return shulker || enchantmentPremium > 0.0 || !contents.isEmpty();
        }

        private PreparedItem withAmount(int newAmount) {
            return new PreparedItem(material, newAmount, shulker, contents, enchantmentPremium);
        }
    }

    private static final class ValuationContext {
        private final int maxRecipeDepth;
        private final Set<Material> recipePath = new HashSet<>();
        private final ConcurrentMap<Material, Double> recipeCache;

        private ValuationContext(int maxRecipeDepth) {
            this(maxRecipeDepth, new ConcurrentHashMap<>());
        }

        private ValuationContext(int maxRecipeDepth, ConcurrentMap<Material, Double> recipeCache) {
            this.maxRecipeDepth = maxRecipeDepth;
            this.recipeCache = recipeCache;
        }
    }

    private record ValuationInputs(Map<Material, Double> directValues, RecipeGraph recipeGraph,
                                   double emptyBoxValue, int maxItemDepth) {}

    private record RecipeGraph(Map<Material, List<RecipeSnapshot>> recipesByResult, int recipeCount) {
        private static final RecipeGraph EMPTY = new RecipeGraph(Map.of(), 0);
    }

    private record RecipeSnapshot(int outputAmount, List<IngredientSnapshot> ingredients) {
        private RecipeSnapshot {
            outputAmount = Math.max(1, outputAmount);
            ingredients = List.copyOf(ingredients);
        }
    }

    private record IngredientSnapshot(List<PreparedItem> alternatives) {
        private static final IngredientSnapshot EMPTY = new IngredientSnapshot(List.of());

        private IngredientSnapshot {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    private double genericEquipmentFloor(Material material, ValuationInputs inputs) {
        String name = material.name();
        Material ingredient = null;
        if (name.startsWith("IRON_")) ingredient = Material.IRON_INGOT;
        else if (name.startsWith("GOLDEN_")) ingredient = Material.GOLD_INGOT;
        else if (name.startsWith("NETHERITE_")) ingredient = Material.NETHERITE_INGOT;
        else if (name.startsWith("STONE_")) ingredient = Material.COBBLESTONE;
        else if (name.startsWith("WOODEN_")) ingredient = Material.OAK_PLANKS;
        else if (name.startsWith("CHAINMAIL_")) ingredient = Material.IRON_INGOT;
        if (ingredient == null) return 0.0;
        int count = componentCount(name);
        if (count <= 0) return 0.0;
        double total = directValue(ingredient, inputs) * count;
        if (name.endsWith("_SWORD") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) {
            total += directValue(Material.STICK, inputs);
        }
        return total;
    }

    private int componentCount(String name) {
        if (name.endsWith("_SWORD")) return 2;
        if (name.endsWith("_PICKAXE") || name.endsWith("_AXE")) return 3;
        if (name.endsWith("_SHOVEL")) return 1;
        if (name.endsWith("_HOE")) return 2;
        if (name.endsWith("_HELMET")) return 5;
        if (name.endsWith("_CHESTPLATE")) return 8;
        if (name.endsWith("_LEGGINGS")) return 7;
        if (name.endsWith("_BOOTS")) return 4;
        return 0;
    }

    private double enchantmentPremium(ItemStack item) {
        int vanillaLevels = item.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
        double total = vanillaLevels * plugin.ecoConfig().getVanillaEnchantPerLevel();
        for (Map.Entry<String, Integer> enchantment : fotiaEnchantments(item).entrySet()) {
            total += Math.max(0, enchantment.getValue())
                    * plugin.ecoConfig().getFeEnchantPerLevel(enchantment.getKey());
        }
        return finiteNonNegative(total);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> fotiaEnchantments(ItemStack item) {
        initializeFotiaReader();
        if (fePdcManager == null || feGetEnchantments == null) return Map.of();
        try {
            Object raw = feGetEnchantments.invoke(fePdcManager, item);
            if (!(raw instanceof Map<?, ?> entries)) return Map.of();
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Number level)) continue;
                result.put(String.valueOf(entry.getKey()), level.intValue());
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private synchronized void initializeFotiaReader() {
        if (feLookupAttempted) return;
        feLookupAttempted = true;
        try {
            var fe = Bukkit.getPluginManager().getPlugin("FotiaEnchantment");
            if (fe == null || !fe.isEnabled()) return;
            Object enchantManager = fe.getClass().getMethod("getEnchantmentManager").invoke(fe);
            fePdcManager = enchantManager.getClass().getMethod("getPdcManager").invoke(enchantManager);
            feGetEnchantments = fePdcManager.getClass().getMethod("getEnchantments", ItemStack.class);
        } catch (Exception ignored) {
            fePdcManager = null;
            feGetEnchantments = null;
        }
    }

    private static void addDiamondTool(Material material, int diamonds) {
        CRAFTING_FLOORS.put(material, Map.of(Material.DIAMOND, diamonds));
    }
}
