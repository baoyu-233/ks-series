package org.kseco;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 市场核心管理器。
 * 协调挂单、官方收购/出售、玩家交易、税费征收、物物交换。
 */
public final class MarketManager {

    private final KsEco plugin;

    public MarketManager(KsEco plugin) {
        this.plugin = plugin;
    }

    // ---- 玩家挂单出售 ----

    /**
     * 玩家上架物品出售（卖钱）。
     * 先在主线程做本地校验并扣除库存，再异步检查挂单上限+写 DB，失败时退回物品。
     */
    public void listItemForSale(Player seller, ItemStack item, int quantity, double unitPrice) {
        if (item == null || item.getType().isAir() || quantity <= 0 || item.getAmount() < quantity) {
            seller.sendMessage("§c物品数量不足。");
            return;
        }
        if (!Double.isFinite(unitPrice) || unitPrice <= 0 || unitPrice > 1_000_000_000_000d) {
            seller.sendMessage("§c价格无效。");
            return;
        }
        // 上架禁令检查
        if (plugin.banManager().isBanned(seller.getUniqueId(), BanManager.BAN_LISTING)) {
            var detail = plugin.banManager().getBanDetail(seller.getUniqueId(), BanManager.BAN_LISTING);
            String reason = detail != null && detail.get("reason") != null ? String.valueOf(detail.get("reason")) : "";
            seller.sendMessage("§c你已被禁止在市场上架物品。" + (reason.isEmpty() ? "" : " 原因: " + reason));
            return;
        }

        // 先从背包扣除（防止双重上架），失败立即退出
        ItemStack toList = item.clone();
        toList.setAmount(quantity);
        UUID sellerUuid = seller.getUniqueId();
        String sellerName = seller.getName();
        String itemDisplayName = getItemDisplayName(item);
        ListingManager.PreparedListingItem preparedItem = plugin.listingManager().prepareListingItem(toList);
        if (preparedItem == null) {
            seller.sendMessage("§c物品数据无法安全序列化，未创建挂单。");
            return;
        }
        if (!removeFromInventory(seller, toList)) {
            seller.sendMessage("§c背包中物品不足。");
            return;
        }

        int maxListings = plugin.ecoConfig().getMaxListingsPerPlayer();
        // 异步：检查上限 + 写 DB，完成后回主线程通知玩家
        plugin.asyncWorkPool().execute(() -> {
            List<ListingManager.Listing> myListings = plugin.listingManager().getPlayerListings(sellerUuid);
            if (myListings.size() >= maxListings) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (seller.isOnline()) {
                        seller.getInventory().addItem(toList);
                        seller.sendMessage("§c挂单数量已达上限（" + maxListings + "个）。");
                    } else {
                        plugin.storageManager().storeItem(sellerUuid, toList, "LISTING_LIMIT_RETURN");
                    }
                });
                return;
            }
            var listing = plugin.listingManager().createListing(
                    sellerUuid, sellerName, preparedItem, quantity, unitPrice, "SELL");
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                // The row is committed at this point. Queue an immediate protection check even if
                // the seller disconnected while the database operation was running.
                if (listing != null) {
                    plugin.officialMarketSweepManager().evaluateNewListing(listing);
                }
                if (listing == null) {
                    if (seller.isOnline()) {
                        seller.getInventory().addItem(toList);
                        seller.sendMessage("§c创建挂单失败。");
                    } else {
                        plugin.storageManager().storeItem(sellerUuid, toList, "LISTING_CREATE_FAILED");
                    }
                    return;
                }
                if (!seller.isOnline()) return;
                seller.sendMessage("§a已上架 §f" + quantity + "x " +
                        itemDisplayName +
                        " §a单价: " + plugin.vaultHook().format(unitPrice) +
                        " §a总价: " + plugin.vaultHook().format(listing.totalPrice()));
            });
        });
    }

    /**
     * 玩家购买挂单物品（修复 NBT 丢失）。PROPERTY 类型挂单（商品房）走 {@link #buyPropertyListing}。
     * getListing DB 读在异步线程，Vault/fillListing/给物品回主线程执行。
     */
    public void buyListing(Player buyer, String listingId, int quantity) {
        if (quantity <= 0) {
            buyer.sendMessage("§c购买数量必须大于 0。");
            return;
        }
        // async 读挂单，避免主线程 DB 阻塞
        plugin.asyncWorkPool().execute(() -> {
            ListingManager.Listing target = plugin.listingManager().getListing(listingId);
            // 读完回主线程做 Vault/库存操作
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (!buyer.isOnline()) return;
                if (target == null) {
                    buyer.sendMessage("§c该挂单已不存在。");
                    return;
                }
                if (target.isProperty()) {
                    buyPropertyListing(buyer, target);
                    return;
                }
                if (target.quantity() < quantity) {
                    buyer.sendMessage("§c库存不足。挂单剩余: " + target.quantity());
                    return;
                }
                if (target.sellerUuid().equals(buyer.getUniqueId())) {
                    buyer.sendMessage("§c不能购买自己的挂单。");
                    return;
                }

                double totalCost = target.unitPrice() * quantity;
                double taxRate = plugin.getCategoryTaxRate("MARKET_TRADE");
                double tax = Math.max(totalCost * taxRate, plugin.ecoConfig().getMinTax());
                double totalWithTax = totalCost + tax;

                if (!plugin.vaultHook().has(buyer, totalWithTax)) {
                    buyer.sendMessage("§c余额不足。需要: " + plugin.vaultHook().format(totalWithTax) +
                            "（含税: " + plugin.vaultHook().format(tax) + "）");
                    return;
                }

                if (!plugin.vaultHook().withdraw(buyer, totalWithTax)) {
                    buyer.sendMessage("§c扣款失败，请稍后重试。");
                    return;
                }
                reserveAndSettlePurchase(buyer, target, quantity, totalCost,
                        taxRate, tax, totalWithTax);
            });
        });
    }

    private void reserveAndSettlePurchase(Player buyer, ListingManager.Listing target, int quantity,
                                          double totalCost, double taxRate, double tax,
                                          double totalWithTax) {
        UUID buyerUuid = buyer.getUniqueId();
        String buyerName = buyer.getName();
        plugin.asyncWorkPool().execute(() -> {
            ListingManager.PurchaseReservation reservation =
                    plugin.listingManager().reservePurchase(target, buyerUuid, quantity);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (reservation == null) {
                    if (!plugin.vaultHook().deposit(buyer, totalWithTax)) {
                        plugin.getLogger().severe("[市场] 挂单认领失败且无法退款买家: " + target.id());
                    }
                    if (buyer.isOnline()) buyer.sendMessage("§c挂单已被其他玩家购买，已退款。");
                    return;
                }

                var seller = org.bukkit.Bukkit.getOfflinePlayer(target.sellerUuid());
                if (!plugin.vaultHook().deposit(seller, totalCost)) {
                    boolean buyerRefunded = plugin.vaultHook().deposit(buyer, totalWithTax);
                    plugin.asyncWorkPool().execute(() -> {
                        boolean rolledBack = plugin.listingManager().rollbackPurchase(reservation);
                        if (!rolledBack || !buyerRefunded) {
                            plugin.getLogger().severe("[市场] 卖家入账失败且回滚不完整: " + target.id());
                        }
                    });
                    if (buyer.isOnline()) buyer.sendMessage("§c卖家收款失败，交易已回滚。");
                    return;
                }

                plugin.asyncWorkPool().execute(() -> plugin.priceEngine().recordTrade(
                        target.itemMaterial(), quantity, target.unitPrice(), buyerUuid.toString(),
                        target.sellerUuid().toString(), "PLAYER"));
                recordTax(buyerUuid.toString(), buyerName, "MARKET_TRADE",
                        totalCost, taxRate, tax, "购买挂单 " + target.id());

                if (buyer.isOnline()) {
                    buyer.sendMessage("§a购买成功！物品已放入暂存箱。花费: "
                            + plugin.vaultHook().format(totalWithTax)
                            + "（含税: " + plugin.vaultHook().format(tax) + "）");
                }
                var onlineSeller = org.bukkit.Bukkit.getPlayer(target.sellerUuid());
                if (onlineSeller != null) {
                    onlineSeller.sendMessage("§a你的挂单已售出 " + quantity + "x，收入: "
                            + plugin.vaultHook().format(totalCost));
                }
            });
        });
    }

    /**
     * 购买商品房挂单：扣款/转账/记税流程与普通挂单一致，但不走"给物品"，而是反射调用
     * ks-Eco-RealEstate 模块的 transferHouseOwnership 转移房屋产权（地块所有权不变）。
     * 房地产模块未加载/转移失败时整单回滚（不能扣钱却不给房）。
     * 注意：此方法必须在主线程调用（Vault 操作要求）。
     */
    private void buyPropertyListing(Player buyer, ListingManager.Listing target) {
        if (target.sellerUuid().equals(buyer.getUniqueId())) {
            buyer.sendMessage("§c不能购买自己的挂单。");
            return;
        }
        String houseId = target.assetRef();
        Map<String, Object> house = getHouseInfo(houseId);
        if (house == null) {
            buyer.sendMessage("§c房屋不存在或地产模块未加载。");
            return;
        }
        String expectedOwnerType = String.valueOf(house.get("ownerType"));
        String expectedOwnerId = String.valueOf(house.get("ownerId"));
        boolean sellerStillOwns = "PLAYER".equals(expectedOwnerType)
                ? target.sellerUuid().toString().equals(expectedOwnerId)
                : Boolean.TRUE.equals(callRealEstate("isEnterpriseMember",
                        new Class<?>[]{String.class, UUID.class}, expectedOwnerId, target.sellerUuid()));
        if (!sellerStillOwns) {
            buyer.sendMessage("§c卖家已不再拥有该房屋，挂单无法成交。");
            return;
        }
        double totalCost = target.unitPrice();
        // 流转税率（"契税率"）：房屋自己的税率（继承自所在地块/区域，管理员划区时配的），不是全局统一税率；
        // 只在产权转移这一刻收一次，平时持有不收任何费用（不做年付/维护费）。
        double taxRate = houseTaxRate(houseId);
        double tax = Math.max(totalCost * taxRate, plugin.ecoConfig().getMinTax());
        double totalWithTax = totalCost + tax;

        if (!plugin.vaultHook().has(buyer, totalWithTax)) {
            buyer.sendMessage("§c余额不足。需要: " + plugin.vaultHook().format(totalWithTax) +
                    "（含税: " + plugin.vaultHook().format(tax) + "）");
            return;
        }

        if (!plugin.vaultHook().withdraw(buyer, totalWithTax)) {
            buyer.sendMessage("§c扣款失败，请稍后重试。");
            return;
        }

        String transferError = transferHouseOwnership(houseId, expectedOwnerType, expectedOwnerId,
                "PLAYER", buyer.getUniqueId().toString());
        if (transferError != null) {
            if (!plugin.vaultHook().deposit(buyer, totalWithTax)) {
                plugin.getLogger().severe("[市场] 商品房交易失败且退款失败: house=" + houseId);
            }
            buyer.sendMessage("§c购买失败（房屋转移异常: " + transferError + "），已退款。");
            plugin.getLogger().warning("[市场] 商品房交易失败已回滚: house=" + houseId + " buyer=" + buyer.getUniqueId() + " 原因=" + transferError);
            return;
        }

        var seller = org.bukkit.Bukkit.getOfflinePlayer(target.sellerUuid());
        if (!plugin.vaultHook().deposit(seller, totalCost)) {
            plugin.getLogger().severe("[市场] 商品房已转移但卖家入账失败: house=" + houseId);
            buyer.sendMessage("§e房屋已转移，卖家入账待管理员处理。");
            return;
        }
        if (!plugin.listingManager().fillListing(target.id(), buyer.getUniqueId(), 1)) {
            plugin.getLogger().severe("[市场] 商品房已转移但挂单状态更新失败: house=" + houseId);
        }

        buyer.sendMessage("§a购房成功！花费: " + plugin.vaultHook().format(totalWithTax) +
                "（含税: " + plugin.vaultHook().format(tax) + "），房屋ID: " + houseId);
        var onlineSeller = org.bukkit.Bukkit.getPlayer(target.sellerUuid());
        if (onlineSeller != null) {
            onlineSeller.sendMessage("§a你的商品房 " + houseId + " 已售出，收入: " + plugin.vaultHook().format(totalCost));
        }
        // 税收记录异步，不阻塞主线程
        recordTax(buyer.getUniqueId().toString(), buyer.getName(), "PROPERTY_TRADE",
                totalCost, taxRate, tax, "购买商品房 " + houseId);
    }

    /** 读房屋自己的流转税率（继承自地块/区域的"契税率"）；房屋不存在/模块未加载时回退到全局 PROPERTY_TRADE 类目税率。 */
    private double houseTaxRate(String houseId) {
        Object houseObj = callRealEstate("getHouse", new Class<?>[]{String.class}, houseId);
        if (houseObj instanceof Map<?, ?> house && house.get("taxRate") instanceof Number n) {
            return n.doubleValue();
        }
        return plugin.getCategoryTaxRate("PROPERTY_TRADE");
    }

    /** 反射桥接 ks-eco-realestate 模块的 transferHouseOwnership。@return null 成功，否则中文错误信息。 */
    private String transferHouseOwnership(String houseId, String expectedOwnerType, String expectedOwnerId,
                                          String newOwnerType, String newOwnerId) {
        if (plugin.extraModuleLoader() == null) return "房地产模块未加载";
        Object module = plugin.extraModuleLoader().getModule("ks-eco-realestate");
        if (module == null) return "房地产模块未加载";
        try {
            Object manager = module.getClass().getMethod("realEstateManager").invoke(module);
            if (manager == null) return "房地产模块未初始化";
            Object result = manager.getClass()
                    .getMethod("transferHouseOwnership", String.class, String.class, String.class,
                            String.class, String.class)
                    .invoke(manager, houseId, expectedOwnerType, expectedOwnerId, newOwnerType, newOwnerId);
            return (String) result; // null=成功
        } catch (Exception e) {
            plugin.getLogger().warning("[市场] 反射调用房屋转移失败: " + e.getMessage());
            return "桥接调用异常";
        }
    }

    /**
     * 玩家上架商品房出售。校验：房屋存在、卖家是该房屋所有者（个人本人 / 企业成员）、未有重复挂单。
     */
    public boolean listHouseForSale(Player seller, String houseId, double price) {
        if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) {
            seller.sendMessage("§c价格必须是有效正数。");
            return false;
        }
        Object houseObj = callRealEstate("getHouse", new Class<?>[]{String.class}, houseId);
        if (!(houseObj instanceof Map<?, ?> house)) {
            seller.sendMessage("§c房屋不存在，或房地产模块未加载。");
            return false;
        }
        String ownerType = (String) house.get("ownerType");
        String ownerId = (String) house.get("ownerId");
        boolean owns;
        if ("PLAYER".equals(ownerType)) {
            owns = seller.getUniqueId().toString().equals(ownerId);
        } else {
            Object isMember = callRealEstate("isEnterpriseMember", new Class<?>[]{String.class, UUID.class}, ownerId, seller.getUniqueId());
            owns = Boolean.TRUE.equals(isMember);
        }
        if (!owns) {
            seller.sendMessage("§c你不是该房屋的所有者。");
            return false;
        }
        if (plugin.listingManager().hasActivePropertyListing(houseId)) {
            seller.sendMessage("§c该房屋已有挂单在售。");
            return false;
        }
        var listing = plugin.listingManager().createPropertyListing(seller.getUniqueId(), seller.getName(), houseId, price);
        if (listing == null) {
            seller.sendMessage("§c上架失败。");
            return false;
        }
        seller.sendMessage("§a已上架商品房 " + houseId + "，价格: " + plugin.vaultHook().format(price));
        return true;
    }

    /** 反射桥接 ks-eco-realestate 模块的 RealEstateManager 方法。模块未加载/异常时返回 null。 */
    /** 反射查询某房屋的详情 Map（id/plotId/zoneId/world/x1..z2/ownerType/ownerId/taxRate/name 等）；模块未加载/房屋不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getHouseInfo(String houseId) {
        Object result = callRealEstate("getHouse", new Class<?>[]{String.class}, houseId);
        return result instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public Object callRealEstate(String method, Class<?>[] argTypes, Object... args) {
        if (plugin.extraModuleLoader() == null) return null;
        Object module = plugin.extraModuleLoader().getModule("ks-eco-realestate");
        if (module == null) return null;
        try {
            Object manager = module.getClass().getMethod("realEstateManager").invoke(module);
            if (manager == null) return null;
            return manager.getClass().getMethod(method, argTypes).invoke(manager, args);
        } catch (Exception e) {
            plugin.getLogger().warning("[市场] 反射调用房地产模块失败(" + method + "): " + e.getMessage());
            return null;
        }
    }

    /** 同 {@link #callRealEstate}，但走 landPerkManager()（地块福利：农业增产/工业加速/官方收购溢价等）。 */
    private Object callLandPerk(String method, Class<?>[] argTypes, Object... args) {
        if (plugin.extraModuleLoader() == null) return null;
        Object module = plugin.extraModuleLoader().getModule("ks-eco-realestate");
        if (module == null) return null;
        try {
            Object manager = module.getClass().getMethod("landPerkManager").invoke(module);
            if (manager == null) return null;
            return manager.getClass().getMethod(method, argTypes).invoke(manager, args);
        } catch (Exception e) {
            plugin.getLogger().warning("[市场] 反射调用地块福利模块失败(" + method + "): " + e.getMessage());
            return null;
        }
    }

    /** 农业地块官方收购溢价百分比：卖家此刻是否享有 AGRICULTURAL 地块福利（PHYSICAL/OWNERSHIP 两种模式由 LandPerkManager 内部判断）。 */
    private double agriOfficialPremiumPct(Player player) {
        Object eligible = callLandPerk("isEligibleForZonePerk",
                new Class<?>[]{java.util.UUID.class, String.class, int.class, int.class, String.class},
                player.getUniqueId(), player.getLocation().getWorld().getName(),
                player.getLocation().getBlockX(), player.getLocation().getBlockZ(), "AGRICULTURAL");
        if (!Boolean.TRUE.equals(eligible)) return 0.0;
        Object pct = callLandPerk("getPerkValue", new Class<?>[]{String.class, double.class}, "agri_official_premium_pct", 0.10);
        return pct instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ---- 官方收购 ----

    public boolean sellToOfficial(Player player, ItemStack item) {
        if (!plugin.ecoConfig().isOfficialBuyEnabled()) {
            player.sendMessage("§c官方收购当前未开放。");
            return false;
        }
        ItemStack original = item == null ? null : item.clone();
        OfficialBuyManager.OfficialExtraction extraction = plugin.officialBuyManager()
                .extractAccepted(original, Integer.MAX_VALUE);
        double totalValue = extraction.totalValue();
        if (totalValue <= 0) {
            player.sendMessage(ShulkerBoxParser.isShulkerBox(item)
                    ? "§c这个潜影盒内没有官方收购的物品。" : "§c官方不收此物品。");
            return false;
        }
        double premiumPct = agriOfficialPremiumPct(player);
        if (premiumPct > 0) totalValue *= (1.0 + premiumPct);
        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack current = player.getInventory().getItem(heldSlot);
        if (current == null || original == null || current.getAmount() != original.getAmount()
                || !current.isSimilar(original)) {
            player.sendMessage("§c主手物品已变化，请重试。");
            return false;
        }
        player.getInventory().setItem(heldSlot,
                extraction.updatedSource() == null ? null : extraction.updatedSource().clone());
        if (!plugin.vaultHook().deposit(player, totalValue)) {
            player.getInventory().setItem(heldSlot, original);
            player.sendMessage("§c收款失败，物品已恢复。");
            return false;
        }

        double priceMultiplier = premiumPct > 0 ? 1.0 + premiumPct : 1.0;
        List<PriceEngine.TradeRecord> trades = new java.util.ArrayList<>();
        String sellerUuid = player.getUniqueId().toString();
        for (ItemStack sold : extraction.removedItems()) {
            double unitPrice = plugin.priceEngine().getOfficialBuyPrice(sold.getType().name()) * priceMultiplier;
            trades.add(new PriceEngine.TradeRecord(sold.getType().name(), sold.getAmount(),
                    unitPrice, "OFFICIAL", sellerUuid, "OFFICIAL_SELL"));
        }
        if (!trades.isEmpty()) {
            List<PriceEngine.TradeRecord> completedTrades = List.copyOf(trades);
            plugin.asyncWorkPool().execute(() -> plugin.priceEngine().recordTrades(completedTrades));
        }
        player.updateInventory();
        player.sendMessage("§a官方收购完成！出售 " + extraction.quantity() + " 个物品，获得: "
                + plugin.vaultHook().format(totalValue)
                + (ShulkerBoxParser.isShulkerBox(original) ? "§a（潜影盒和未收购内容已保留）" : ""));
        return true;
    }

    // ---- 工具 ----

    /** 先尝试给玩家背包，满了入暂存箱 */
    private void giveOrStore(Player player, ItemStack item, String source) {
        var leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack is : leftover.values()) {
                plugin.storageManager().storeItem(player.getUniqueId(), is, source + "_OVERFLOW");
            }
            player.sendMessage("§e背包已满，部分物品已放入暂存箱 (/storage)。");
        }
        player.updateInventory();
    }

    private boolean removeFromInventory(Player player, ItemStack item) {
        PlayerInventory inv = player.getInventory();
        int remaining = item.getAmount();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) continue;
            if (slot.isSimilar(item)) {
                int take = Math.min(remaining, slot.getAmount());
                slot.setAmount(slot.getAmount() - take);
                remaining -= take;
                if (slot.getAmount() <= 0) inv.setItem(i, null);
                if (remaining <= 0) break;
            }
        }
        return remaining <= 0;
    }

    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        return item.getType().name();
    }

    /** 记录税收到 ks_tax_records 表（异步执行，不阻塞主线程） */
    private void recordTax(String payerUuid, String payerName, String category,
                           double baseAmount, double taxRate, double taxAmount, String description) {
        if (taxAmount <= 0) return;
        plugin.asyncWorkPool().execute(() -> {
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_records (payer_uuid, payer_name, category, " +
                        "base_amount, tax_rate, tax_amount, description, collected_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, payerUuid);
                    ps.setString(2, payerName);
                    ps.setString(3, category);
                    ps.setDouble(4, baseAmount);
                    ps.setDouble(5, taxRate);
                    ps.setDouble(6, taxAmount);
                    ps.setString(7, description);
                    ps.setLong(8, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("记录税收失败: " + e.getMessage());
            }
        });
    }
}
