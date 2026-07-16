package org.kseco.extra.realestate;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

public final class LandPerkListener implements Listener {

    private static final Set<Material> AGEABLE_HARVEST_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA);
    private static final Set<Material> SIMPLE_HARVEST_BLOCKS = Set.of(
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.MELON, Material.PUMPKIN);

    private final LandPerkManager perks;

    public LandPerkListener(LandPerkManager perks) {
        this.perks = perks;
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!(event.getNewState().getBlockData() instanceof Ageable ageable)) return;
        Block block = event.getBlock();
        if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL)) {
            return;
        }
        if (!perks.allowAgricultureEvent()) return;
        int steps = perks.getBlockGrowthSteps(block.getWorld().getName(), block.getX(), block.getZ());
        if (applyGrowthBoost(ageable, steps) <= 0) return;
        event.getNewState().setBlockData(ageable);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (!(state.getBlockData() instanceof Ageable ageable)) continue;
            Block block = state.getBlock();
            if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                    RealEstateManager.ZONE_TYPE_AGRICULTURAL)) {
                continue;
            }
            if (!perks.allowAgricultureEvent()) continue;
            int steps = perks.getBlockGrowthSteps(block.getWorld().getName(), block.getX(), block.getZ());
            if (applyGrowthBoost(ageable, steps) > 0) {
                state.setBlockData(ageable);
            }
        }
    }

    static int applyGrowthBoost(Ageable ageable, int steps) {
        if (steps <= 0) return 0;
        int current = ageable.getAge();
        int boostedAge = Math.min(current + steps, ageable.getMaximumAge());
        if (boostedAge <= current) return 0;
        ageable.setAge(boostedAge);
        return boostedAge - current;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvestDrop(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!isBonusHarvestBlock(state)) return;
        if (event.getItems().isEmpty()) return;
        Block block = event.getBlock();
        if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL)) {
            return;
        }
        if (!perks.allowAgricultureEvent()) return;
        double chance = perks.getBlockPerkValue(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL, "agri_harvest_yield_bonus_chance", 0.20);
        if (Math.random() >= chance) return;
        ItemStack bonus = event.getItems().get(0).getItemStack().clone();
        bonus.setAmount(1);
        block.getWorld().dropItemNaturally(block.getLocation(), bonus);
    }

    private boolean isBonusHarvestBlock(BlockState state) {
        Material type = state.getType();
        if (AGEABLE_HARVEST_CROPS.contains(type)) {
            return state.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
        }
        return SIMPLE_HARVEST_BLOCKS.contains(type);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvestBerry(PlayerHarvestBlockEvent event) {
        Block block = event.getHarvestedBlock();
        if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL)) {
            return;
        }
        if (!perks.allowAgricultureEvent()) return;
        double chance = perks.getBlockPerkValue(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL, "agri_harvest_yield_bonus_chance", 0.20);
        if (Math.random() >= chance) return;
        List<ItemStack> items = event.getItemsHarvested();
        if (items.isEmpty()) return;
        ItemStack bonus = items.get(0).clone();
        bonus.setAmount(1);
        items.add(bonus);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceStart(FurnaceStartSmeltEvent event) {
        Block block = event.getBlock();
        if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_INDUSTRIAL)) {
            return;
        }
        perks.rememberFurnaceBlock(block);
        if (!perks.allowIndustryEvent()) return;
        double speedPct = perks.getBlockPerkValue(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_INDUSTRIAL, "industry_furnace_speed_pct", 0.20);
        int total = event.getTotalCookTime();
        int reduced = (int) Math.max(1, Math.round(total * (1 - Math.min(0.95, Math.max(0, speedPct)))));
        event.setTotalCookTime(reduced);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        Block block = event.getBlock();
        if (!perks.isBlockEligibleForZonePerk(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_INDUSTRIAL)) {
            return;
        }
        perks.rememberFurnaceBlock(block);
        if (!perks.allowIndustryEvent()) return;
        double chance = perks.getBlockPerkValue(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_INDUSTRIAL, "industry_furnace_bonus_output_chance", 0.10);
        if (Math.random() >= chance) return;
        ItemStack result = event.getResult();
        if (result.getAmount() < result.getType().getMaxStackSize()) {
            ItemStack bonus = result.clone();
            bonus.setAmount(result.getAmount() + 1);
            event.setResult(bonus);
        }
    }
}
