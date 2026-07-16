package org.kseries.rpg;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

final class MaterialExchangeService {
    enum Result { SUCCESS, MMOITEMS_UNAVAILABLE, OUTPUT_INVALID, MISSING_INPUTS, INVENTORY_FULL }

    private final MmoItemsBridge mmoItems;

    MaterialExchangeService(MmoItemsBridge mmoItems) {
        this.mmoItems = mmoItems;
    }

    Result exchange(Player player, Catalog.Exchange recipe, int multiplier) {
        if (multiplier < 1 || multiplier > 64) return Result.MISSING_INPUTS;
        ItemStack unit = mmoItems.create(recipe.output().type(), recipe.output().id());
        if (unit == null) return Result.MMOITEMS_UNAVAILABLE;

        int outputAmount;
        try {
            outputAmount = Math.multiplyExact(recipe.output().amount(), multiplier);
        } catch (ArithmeticException ex) {
            return Result.OUTPUT_INVALID;
        }
        if (outputAmount < 1 || !canFit(player.getInventory(), unit, outputAmount)) return Result.INVENTORY_FULL;

        for (Catalog.Input input : recipe.inputs()) {
            int required;
            try {
                required = Math.multiplyExact(input.amount(), multiplier);
            } catch (ArithmeticException ex) {
                return Result.MISSING_INPUTS;
            }
            if (count(player.getInventory(), input) < required) return Result.MISSING_INPUTS;
        }

        for (Catalog.Input input : recipe.inputs()) {
            remove(player.getInventory(), input, input.amount() * multiplier);
        }
        give(player.getInventory(), unit, outputAmount);
        return Result.SUCCESS;
    }

    private int count(PlayerInventory inventory, Catalog.Input input) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (matches(item, input)) total += item.getAmount();
        }
        return total;
    }

    private void remove(PlayerInventory inventory, Catalog.Input input, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!matches(item, input)) continue;
            int taken = Math.min(remaining, item.getAmount());
            remaining -= taken;
            if (taken == item.getAmount()) contents[slot] = null;
            else item.setAmount(item.getAmount() - taken);
        }
        inventory.setStorageContents(contents);
    }

    private boolean matches(ItemStack item, Catalog.Input input) {
        if (item == null || item.getType().isAir()) return false;
        if (input.kind() == Catalog.InputKind.VANILLA) return item.getType() == input.material();
        return mmoItems.matches(item, input.type(), input.id());
    }

    private boolean canFit(PlayerInventory inventory, ItemStack unit, int amount) {
        int free = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                free += unit.getMaxStackSize();
            } else if (item.isSimilar(unit)) {
                free += Math.max(0, item.getMaxStackSize() - item.getAmount());
            }
            if (free >= amount) return true;
        }
        return false;
    }

    private void give(PlayerInventory inventory, ItemStack unit, int amount) {
        int remaining = amount;
        List<ItemStack> outputs = new ArrayList<>();
        while (remaining > 0) {
            ItemStack copy = unit.clone();
            int stackAmount = Math.min(remaining, copy.getMaxStackSize());
            copy.setAmount(stackAmount);
            outputs.add(copy);
            remaining -= stackAmount;
        }
        if (!inventory.addItem(outputs.toArray(ItemStack[]::new)).isEmpty()) {
            throw new IllegalStateException("Output capacity changed during material exchange");
        }
    }
}
