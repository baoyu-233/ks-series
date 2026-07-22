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
import java.util.concurrent.RejectedExecutionException;

/**
 * 市场核心管理器。
 * 协调挂单、官方收购/出售、玩家交易、税费征收、物物交换。
 */
public final class MarketManager {

    private final KsEco plugin;

    public MarketManager(KsEco plugin) {
        this.plugin = plugin;
        plugin.scheduler().runGlobalLater(() -> {
            recoverMarketSettlements();
            recoverPropertySettlements();
        }, 40L);
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
        plugin.asyncWorkPool().executeDatabase(() -> {
            List<ListingManager.Listing> myListings = plugin.listingManager().getPlayerListings(sellerUuid);
            if (myListings.size() >= maxListings) {
                plugin.scheduler().runEntity(seller, () -> {
                    if (seller.isOnline()) {
                        seller.getInventory().addItem(toList);
                        seller.sendMessage("§c挂单数量已达上限（" + maxListings + "个）。");
                    } else {
                        plugin.storageManager().storeItem(sellerUuid, toList, "LISTING_LIMIT_RETURN");
                    }
                }, () -> { });
                return;
            }
            var listing = plugin.listingManager().createListing(
                    sellerUuid, sellerName, preparedItem, quantity, unitPrice, "SELL");
            plugin.scheduler().runEntity(seller, () -> {
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
            }, () -> { });
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
        plugin.asyncWorkPool().executeDatabase(() -> {
            ListingManager.Listing target = plugin.listingManager().getListing(listingId);
            // 读完回主线程做 Vault/库存操作
            plugin.scheduler().runEntity(buyer, () -> {
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

                beginMarketPurchase(buyer.getUniqueId(), buyer.getName(), target, quantity,
                        totalCost, taxRate, tax, totalWithTax);
            }, () -> { });
        });
    }

    private void beginMarketPurchase(UUID buyerUuid, String buyerName, ListingManager.Listing target, int quantity,
                                     double totalCost, double taxRate, double tax, double totalWithTax) {
        String settlementId = "MK-" + UUID.randomUUID();
        MarketPurchaseSettlementStore.Settlement settlement = new MarketPurchaseSettlementStore.Settlement(
                settlementId, target.id(), null, buyerUuid, buyerName, target.sellerUuid(), quantity,
                totalCost, taxRate, tax, totalWithTax,
                MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED, "", "");
        executeDatabaseOrReject(() -> {
            boolean prepared = false;
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection != null) {
                    MarketPurchaseSettlementStore.initialize(connection);
                    MarketPurchaseSettlementStore.insertChargeClaim(connection, settlement, now());
                    prepared = true;
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("[市场] 无法创建购买结算: " + exception.getMessage());
            }
            boolean ready = prepared;
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                if (!ready) {
                    sendBuyerMessage(buyerUuid, "§c无法创建交易结算，请稍后重试。");
                    return;
                }
                chargeMarketBuyer(settlement, target);
            });
        }, () -> sendBuyerMessage(buyerUuid, "§c市场队列繁忙，未扣款，请稍后重试。"));
    }

    private void chargeMarketBuyer(MarketPurchaseSettlementStore.Settlement settlement,
                                   ListingManager.Listing target) {
        Player buyer = org.bukkit.Bukkit.getPlayer(settlement.buyerUuid());
        if (buyer == null || !buyer.isOnline()) {
            transitionSettlementAsync(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                    MarketPurchaseSettlementStore.COMPENSATED, "buyer disconnected before charge");
            return;
        }
        if (plugin.vaultHook().directBuiltinActive()) {
            executeDatabaseOrReject(() -> {
                boolean charged = plugin.vaultHook().withdrawDirect(
                        settlement.buyerUuid(), settlement.buyerName(), settlement.totalCharge());
                plugin.scheduler().runEntity(buyer, () -> {
                    if (charged) reserveChargedPurchase(settlement, target);
                    else {
                        transitionSettlementAsync(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                                MarketPurchaseSettlementStore.COMPENSATED, "builtin buyer charge rejected");
                        buyer.sendMessage("§c余额不足或扣款失败。");
                    }
                }, () -> {
                    if (charged) markSettlementReviewAsync(settlement.id(),
                            MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                            "buyer disconnected after builtin charge");
                });
            }, () -> buyer.sendMessage("§c市场队列繁忙，未扣款，请稍后重试。"));
            return;
        }
        if (!plugin.vaultHook().has(buyer, settlement.totalCharge())) {
            transitionSettlementAsync(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                    MarketPurchaseSettlementStore.COMPENSATED, "insufficient balance");
            buyer.sendMessage("§c余额已发生变化，交易未扣款。");
            return;
        }
        if (!plugin.vaultHook().withdraw(buyer, settlement.totalCharge())) {
            transitionSettlementAsync(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                    MarketPurchaseSettlementStore.COMPENSATED, "buyer charge rejected");
            buyer.sendMessage("§c扣款失败，请稍后重试。");
            return;
        }
        reserveChargedPurchase(settlement, target);
    }

    private void reserveChargedPurchase(MarketPurchaseSettlementStore.Settlement settlement,
                                        ListingManager.Listing target) {
        executeDatabaseOrReject(() -> {
            boolean charged = transitionSettlement(settlement.id(),
                    MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                    MarketPurchaseSettlementStore.BUYER_CHARGED, "");
            ListingManager.PurchaseReservation reservation = charged
                    ? plugin.listingManager().reservePurchase(target, settlement.buyerUuid(),
                    settlement.quantity(), settlement.id()) : null;
            if (charged && reservation == null) {
                transitionSettlement(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGED,
                        MarketPurchaseSettlementStore.REFUND_READY, "listing reservation rejected");
            }
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                if (!charged) {
                    markSettlementReviewAsync(settlement.id(), MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                            "buyer was charged but journal transition failed");
                    return;
                }
                if (reservation == null) {
                    refundMarketBuyer(settlement);
                    return;
                }
                claimSellerPayout(settlement, target, reservation);
            });
        }, () -> markSettlementReviewAsync(settlement.id(),
                MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                "database queue rejected after buyer charge"));
    }

    private void claimSellerPayout(MarketPurchaseSettlementStore.Settlement settlement,
                                   ListingManager.Listing target,
                                   ListingManager.PurchaseReservation reservation) {
        executeDatabaseOrReject(() -> {
            boolean claimed = transitionSettlement(settlement.id(), MarketPurchaseSettlementStore.RESERVED,
                    MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED, "");
            Runnable payout = () -> {
                if (!claimed) return;
                boolean paid = plugin.vaultHook().directBuiltinActive()
                        ? plugin.vaultHook().depositDirect(settlement.sellerUuid(),
                                target.sellerName(), settlement.totalCost())
                        : plugin.vaultHook().deposit(
                                org.bukkit.Bukkit.getOfflinePlayer(settlement.sellerUuid()), settlement.totalCost());
                if (paid) {
                    finalizeMarketPurchase(settlement, target, reservation);
                } else {
                    rollbackMarketReservation(settlement, reservation,
                            MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED,
                            "seller payout rejected");
                }
            };
            if (plugin.vaultHook().directBuiltinActive()) payout.run();
            else plugin.scheduler().runEntityOrGlobal(settlement.sellerUuid(), payout);
        }, () -> plugin.getLogger().warning("[市场] 卖家入账认领排队失败，启动恢复将重试: " + settlement.id()));
    }

    private void finalizeMarketPurchase(MarketPurchaseSettlementStore.Settlement settlement,
                                        ListingManager.Listing target,
                                        ListingManager.PurchaseReservation reservation) {
        executeDatabaseOrReject(() -> {
            boolean finalized = plugin.listingManager().finalizePurchase(reservation, settlement.id());
            if (!finalized) {
                markSettlementReview(settlement.id(), MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED,
                        "seller paid but delivery finalization failed");
                return;
            }
            plugin.priceEngine().recordTrade(target.itemMaterial(), settlement.quantity(), target.unitPrice(),
                    settlement.buyerUuid().toString(), settlement.sellerUuid().toString(), "PLAYER");
            recordTax(settlement.buyerUuid().toString(), settlement.buyerName(), "MARKET_TRADE",
                    settlement.totalCost(), settlement.taxRate(), settlement.tax(), "购买挂单 " + target.id());
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                sendBuyerMessage(settlement.buyerUuid(), "§a购买成功！物品已放入暂存箱。花费: "
                        + plugin.vaultHook().format(settlement.totalCharge())
                        + "（含税: " + plugin.vaultHook().format(settlement.tax()) + "）");
                Player seller = org.bukkit.Bukkit.getPlayer(settlement.sellerUuid());
                if (seller != null) seller.sendMessage("§a你的挂单已售出 " + settlement.quantity()
                        + "x，收入: " + plugin.vaultHook().format(settlement.totalCost()));
            });
        }, () -> markSettlementReviewAsync(settlement.id(),
                MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED,
                "database queue rejected after seller payout"));
    }

    private void rollbackMarketReservation(MarketPurchaseSettlementStore.Settlement settlement,
                                           ListingManager.PurchaseReservation reservation,
                                           String expectedState, String reason) {
        executeDatabaseOrReject(() -> {
            boolean rolledBack = plugin.listingManager().rollbackPurchase(
                    reservation, settlement.id(), expectedState);
            if (!rolledBack) {
                markSettlementReview(settlement.id(), expectedState,
                        reason + "; reservation rollback failed");
                return;
            }
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> refundMarketBuyer(settlement));
        }, () -> markSettlementReviewAsync(settlement.id(), expectedState,
                reason + "; rollback queue rejected"));
    }

    private void refundMarketBuyer(MarketPurchaseSettlementStore.Settlement settlement) {
        executeDatabaseOrReject(() -> {
            boolean claimed = transitionSettlement(settlement.id(), MarketPurchaseSettlementStore.REFUND_READY,
                    MarketPurchaseSettlementStore.REFUND_CLAIMED, "");
            Runnable refund = () -> {
                if (!claimed) return;
                boolean refunded = plugin.vaultHook().directBuiltinActive()
                        ? plugin.vaultHook().depositDirect(settlement.buyerUuid(),
                                settlement.buyerName(), settlement.totalCharge())
                        : plugin.vaultHook().deposit(
                                org.bukkit.Bukkit.getOfflinePlayer(settlement.buyerUuid()), settlement.totalCharge());
                transitionSettlementAsync(settlement.id(), MarketPurchaseSettlementStore.REFUND_CLAIMED,
                        refunded ? MarketPurchaseSettlementStore.COMPENSATED
                                : MarketPurchaseSettlementStore.REFUND_READY,
                        refunded ? "" : "buyer refund rejected");
                if (refunded) sendBuyerMessage(settlement.buyerUuid(), "§c挂单成交失败，扣款已退回。");
            };
            if (plugin.vaultHook().directBuiltinActive()) refund.run();
            else plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), refund);
        }, () -> plugin.getLogger().warning("[市场] 退款认领排队失败，启动恢复将重试: " + settlement.id()));
    }

    private void recoverMarketSettlements() {
        executeDatabaseOrReject(() -> {
            List<RecoveredPayout> resumePayouts = new java.util.ArrayList<>();
            List<MarketPurchaseSettlementStore.Settlement> refunds = new java.util.ArrayList<>();
            int reviews = 0;
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                MarketPurchaseSettlementStore.initialize(connection);
                reviews = MarketPurchaseSettlementStore.markUnknownCallsForReview(connection, now());
                for (MarketPurchaseSettlementStore.Settlement settlement
                        : MarketPurchaseSettlementStore.open(connection)) {
                    switch (settlement.status()) {
                        case MarketPurchaseSettlementStore.BUYER_CHARGED -> {
                            if (MarketPurchaseSettlementStore.transition(connection, settlement.id(),
                                    MarketPurchaseSettlementStore.BUYER_CHARGED,
                                    MarketPurchaseSettlementStore.REFUND_READY,
                                    "startup refund before reservation", now())) refunds.add(settlement);
                        }
                        case MarketPurchaseSettlementStore.RESERVED -> {
                            ListingManager.Listing listing = plugin.listingManager()
                                    .getListingForSettlement(settlement.listingId());
                            if (listing == null || settlement.storageId() == null) {
                                MarketPurchaseSettlementStore.transition(connection, settlement.id(),
                                        MarketPurchaseSettlementStore.RESERVED,
                                        MarketPurchaseSettlementStore.REVIEW_REQUIRED,
                                        "startup could not rebuild listing reservation", now());
                            } else {
                                ListingManager.PurchaseReservation reservation = new ListingManager.PurchaseReservation(
                                        settlement.storageId(), settlement.listingId(), settlement.buyerUuid(),
                                        settlement.quantity(), ListingManager.DEFAULT_CURRENCY_ID, settlement.totalCost());
                                resumePayouts.add(new RecoveredPayout(settlement, listing, reservation));
                            }
                        }
                        case MarketPurchaseSettlementStore.REFUND_READY -> refunds.add(settlement);
                        default -> { }
                    }
                }
            } catch (SQLException exception) {
                plugin.getLogger().severe("[市场] 结算恢复失败: " + exception.getMessage());
                return;
            }
            int reviewCount = reviews;
            plugin.scheduler().runGlobal(() -> {
                if (reviewCount > 0) plugin.getLogger().severe("[市场] " + reviewCount
                        + " 笔外部钱包结果未知，已进入 REVIEW_REQUIRED");
                for (RecoveredPayout payout : resumePayouts) {
                    claimSellerPayout(payout.settlement(), payout.listing(), payout.reservation());
                }
                refunds.forEach(this::refundMarketBuyer);
            });
        }, () -> plugin.getLogger().warning("[市场] 启动恢复排队失败"));
    }

    private boolean transitionSettlement(String settlementId, String expected, String next, String error) {
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            return connection != null && MarketPurchaseSettlementStore.transition(
                    connection, settlementId, expected, next, error, now());
        } catch (SQLException exception) {
            plugin.getLogger().warning("[市场] 结算状态更新失败 " + settlementId + ": " + exception.getMessage());
            return false;
        }
    }

    private void transitionSettlementAsync(String settlementId, String expected, String next, String error) {
        executeDatabaseOrReject(() -> transitionSettlement(settlementId, expected, next, error),
                () -> plugin.getLogger().severe("[市场] 结算状态排队失败: " + settlementId));
    }

    private void markSettlementReview(String settlementId, String expected, String error) {
        if (!transitionSettlement(settlementId, expected, MarketPurchaseSettlementStore.REVIEW_REQUIRED, error)) {
            plugin.getLogger().severe("[市场] 无法标记人工复核: " + settlementId + " - " + error);
        }
    }

    private void markSettlementReviewAsync(String settlementId, String expected, String error) {
        executeDatabaseOrReject(() -> markSettlementReview(settlementId, expected, error),
                () -> plugin.getLogger().severe("[市场] 人工复核标记排队失败: " + settlementId));
    }

    private boolean executeDatabaseOrReject(Runnable task, Runnable onRejected) {
        try {
            plugin.asyncWorkPool().executeDatabase(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            onRejected.run();
            return false;
        }
    }

    private void sendBuyerMessage(UUID buyerUuid, String message) {
        plugin.scheduler().runPlayer(buyerUuid, buyer -> buyer.sendMessage(message), () -> { });
    }

    private void sendSellerMessage(UUID sellerUuid, String message) {
        plugin.scheduler().runPlayer(sellerUuid, seller -> seller.sendMessage(message), () -> { });
    }

    private boolean isEnterprisePropertySellerAuthorized(String enterpriseId, UUID sellerUuid) {
        var access = plugin.enterpriseAccessProvider();
        return access != null && plugin.enterpriseFundSettlementProvider() != null
                && access.hasPermission(enterpriseId, sellerUuid, EnterprisePermissionService.MANAGE_PROPERTY);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private record RecoveredPayout(MarketPurchaseSettlementStore.Settlement settlement,
                                   ListingManager.Listing listing,
                                   ListingManager.PurchaseReservation reservation) { }

    private void buyPropertyListing(Player buyer, ListingManager.Listing target) {
        if (target.sellerUuid().equals(buyer.getUniqueId())) {
            buyer.sendMessage("§c不能购买自己的挂单。");
            return;
        }
        UUID buyerUuid = buyer.getUniqueId();
        String buyerName = buyer.getName();
        executeDatabaseOrReject(() -> {
            String houseId = target.assetRef();
            Map<String, Object> house = getHouseInfo(houseId);
            if (house == null) {
                sendBuyerMessage(buyerUuid, "§c房屋不存在或地产模块未加载。");
                return;
            }
            String ownerType = String.valueOf(house.get("ownerType"));
            String ownerId = String.valueOf(house.get("ownerId"));
            boolean enterpriseAuthorized = !"ENTERPRISE".equalsIgnoreCase(ownerType)
                    || isEnterprisePropertySellerAuthorized(ownerId, target.sellerUuid());
            PropertySalePolicy.Decision decision = PropertySalePolicy.evaluate(
                    ownerType, ownerId, target.sellerUuid(), enterpriseAuthorized);
            if (!decision.allowed()) {
                sendBuyerMessage(buyerUuid, decision.reason() == PropertySalePolicy.Reason.ENTERPRISE_NOT_AUTHORIZED
                        ? "§c企业房产暂不支持市场成交：企业公户结算尚未接线。"
                        : "§c卖家已不再拥有该房屋，挂单无法成交。");
                return;
            }
            double taxRate = houseTaxRate(houseId);
            double tax = Math.max(target.unitPrice() * taxRate, plugin.ecoConfig().getMinTax());
            PropertyMarketSettlementStore.Settlement settlement = new PropertyMarketSettlementStore.Settlement(
                    "PMS-" + UUID.randomUUID(), target.id(), houseId, buyerUuid, buyerName,
                    target.sellerUuid(), ownerType, ownerId, target.unitPrice(), taxRate, tax,
                    target.unitPrice() + tax, PropertyMarketSettlementStore.BUYER_CHARGE_READY, "", "");
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) throw new SQLException("database unavailable");
                PropertyMarketSettlementStore.initialize(connection);
                PropertyMarketSettlementStore.prepare(connection, settlement, now());
            } catch (SQLException failure) {
                sendBuyerMessage(buyerUuid, "§c挂单状态已变化，请刷新后重试。");
                return;
            }
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> processPropertyBuyerCharge(settlement));
        }, () -> buyer.sendMessage("§c结算队列繁忙，请稍后重试。"));
    }

    private void processPropertyBuyerCharge(PropertyMarketSettlementStore.Settlement settlement) {
        executeDatabaseOrReject(() -> {
            if (!transitionProperty(settlement.id(), PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED, "")) return;
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                boolean charged;
                try {
                    charged = plugin.vaultHook().withdraw(
                            org.bukkit.Bukkit.getOfflinePlayer(settlement.buyerUuid()), settlement.totalCharge());
                } catch (RuntimeException uncertain) {
                    markPropertyReviewAsync(settlement.id(), PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            "buyer charge outcome unknown: " + uncertain.getMessage());
                    return;
                }
                if (!charged) {
                    executeDatabaseOrReject(() -> {
                        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                            if (connection != null && PropertyMarketSettlementStore.compensateBeforeTransfer(
                                    connection, settlement, PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                                    "buyer charge rejected", now())) {
                                sendBuyerMessage(settlement.buyerUuid(), "§c余额不足或扣款被拒绝。");
                            }
                        } catch (SQLException failure) {
                            markPropertyReview(settlement.id(), PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                                    "charge rejected but listing restore failed: " + failure.getMessage());
                        }
                    }, () -> markPropertyReviewAsync(settlement.id(),
                            PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            "charge rejection rollback queue rejected"));
                    return;
                }
                executeDatabaseOrReject(() -> {
                    if (transitionProperty(settlement.id(), PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            PropertyMarketSettlementStore.TRANSFER_READY, "")) {
                        processPropertyTransfer(settlement, false);
                    } else {
                        markPropertyReview(settlement.id(), PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                                "buyer charged but transfer preparation failed");
                    }
                }, () -> markPropertyReviewAsync(settlement.id(),
                        PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                        "post-charge database queue rejected"));
            });
        }, () -> plugin.getLogger().warning("[Property settlement] Buyer charge claim queue rejected: "
                + settlement.id()));
    }

    private void processPropertyTransfer(PropertyMarketSettlementStore.Settlement settlement, boolean alreadyClaimed) {
        if (!alreadyClaimed && !transitionProperty(settlement.id(), PropertyMarketSettlementStore.TRANSFER_READY,
                PropertyMarketSettlementStore.TRANSFER_CLAIMED, "")) return;
        String transferError = transferHouseOwnership(settlement.houseId(), settlement.expectedOwnerType(),
                settlement.expectedOwnerId(), "PLAYER", settlement.buyerUuid().toString());
        Map<String, Object> current = getHouseInfo(settlement.houseId());
        String currentType = current == null ? "" : String.valueOf(current.get("ownerType"));
        String currentId = current == null ? "" : String.valueOf(current.get("ownerId"));
        if ("PLAYER".equalsIgnoreCase(currentType) && settlement.buyerUuid().toString().equals(currentId)) {
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection != null && PropertyMarketSettlementStore.finishTransfer(connection, settlement, now())) {
                    plugin.scheduler().runEntityOrGlobal(settlement.sellerUuid(), () -> processPropertySellerPayout(settlement));
                    return;
                }
            } catch (SQLException failure) {
                transferError = failure.getMessage();
            }
            markPropertyReview(settlement.id(), PropertyMarketSettlementStore.TRANSFER_CLAIMED,
                    "ownership transferred but journal finalization failed: " + transferError);
            return;
        }
        if (settlement.expectedOwnerType().equalsIgnoreCase(currentType)
                && settlement.expectedOwnerId().equals(currentId)) {
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection != null && PropertyMarketSettlementStore.prepareRefund(connection, settlement,
                        PropertyMarketSettlementStore.TRANSFER_CLAIMED,
                        "property transfer rejected: " + transferError, now())) {
                    plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> processPropertyRefund(settlement));
                    return;
                }
            } catch (SQLException failure) {
                transferError = failure.getMessage();
            }
        }
        markPropertyReview(settlement.id(), PropertyMarketSettlementStore.TRANSFER_CLAIMED,
                "property ownership could not be reconciled: " + transferError);
    }

    private void processPropertySellerPayout(PropertyMarketSettlementStore.Settlement settlement) {
        if ("ENTERPRISE".equalsIgnoreCase(settlement.expectedOwnerType())) {
            processEnterprisePropertyPayout(settlement);
            return;
        }
        executeDatabaseOrReject(() -> {
            if (!transitionProperty(settlement.id(), PropertyMarketSettlementStore.SELLER_PAYOUT_READY,
                    PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED, "")) return;
            plugin.scheduler().runEntityOrGlobal(settlement.sellerUuid(), () -> {
                boolean paid;
                try {
                    paid = plugin.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(settlement.sellerUuid()),
                            settlement.saleAmount());
                } catch (RuntimeException uncertain) {
                    markPropertyReviewAsync(settlement.id(), PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED,
                            "seller payout outcome unknown: " + uncertain.getMessage());
                    return;
                }
                if (!paid) {
                    transitionPropertyAsync(settlement.id(), PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED,
                            PropertyMarketSettlementStore.SELLER_PAYOUT_READY, "seller payout rejected");
                    return;
                }
                executeDatabaseOrReject(() -> {
                    if (!transitionProperty(settlement.id(), PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED,
                            PropertyMarketSettlementStore.FINALIZED, "")) {
                        markPropertyReview(settlement.id(), PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED,
                                "seller paid but finalization failed");
                        return;
                    }
                    recordTax(settlement.buyerUuid().toString(), settlement.buyerName(), "PROPERTY_TRADE",
                            settlement.saleAmount(), settlement.taxRate(), settlement.taxAmount(),
                            "购买商品房 " + settlement.houseId());
                    plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                        sendBuyerMessage(settlement.buyerUuid(), "§a购房成功！花费: "
                                + plugin.vaultHook().format(settlement.totalCharge())
                                + "，房屋ID: " + settlement.houseId());
                        Player seller = org.bukkit.Bukkit.getPlayer(settlement.sellerUuid());
                        if (seller != null) seller.sendMessage("§a你的商品房 " + settlement.houseId()
                                + " 已售出，收入: " + plugin.vaultHook().format(settlement.saleAmount()));
                    });
                }, () -> markPropertyReviewAsync(settlement.id(),
                        PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED,
                        "seller paid but finalization queue rejected"));
            });
        }, () -> plugin.getLogger().warning("[Property settlement] Seller payout queue rejected: "
                + settlement.id()));
    }

    private void processEnterprisePropertyPayout(PropertyMarketSettlementStore.Settlement settlement) {
        executeDatabaseOrReject(() -> {
            var provider = plugin.enterpriseFundSettlementProvider();
            if (provider == null) {
                plugin.getLogger().severe("[Property settlement] Enterprise payout provider unavailable: "
                        + settlement.id());
                return;
            }
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) throw new SQLException("database unavailable");
                if (!PropertyMarketSettlementStore.finishEnterprisePayout(
                        connection, settlement, provider, now())) return;
                recordTax(settlement.buyerUuid().toString(), settlement.buyerName(), "PROPERTY_TRADE",
                        settlement.saleAmount(), settlement.taxRate(), settlement.taxAmount(),
                        "enterprise property sale " + settlement.houseId());
                sendBuyerMessage(settlement.buyerUuid(), "§a购房成功！房屋ID: " + settlement.houseId());
                sendSellerMessage(settlement.sellerUuid(), "§a企业房产 " + settlement.houseId()
                        + " 已售出，收入已进入企业公户。");
            } catch (SQLException failure) {
                plugin.getLogger().severe("[Property settlement] Enterprise payout failed " + settlement.id()
                        + ": " + failure.getMessage());
            }
        }, () -> plugin.getLogger().severe("[Property settlement] Enterprise payout queue rejected: "
                + settlement.id()));
    }

    private void processPropertyRefund(PropertyMarketSettlementStore.Settlement settlement) {
        executeDatabaseOrReject(() -> {
            if (!transitionProperty(settlement.id(), PropertyMarketSettlementStore.REFUND_READY,
                    PropertyMarketSettlementStore.REFUND_CLAIMED, "")) return;
            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> {
                boolean refunded;
                try {
                    refunded = plugin.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(settlement.buyerUuid()),
                            settlement.totalCharge());
                } catch (RuntimeException uncertain) {
                    markPropertyReviewAsync(settlement.id(), PropertyMarketSettlementStore.REFUND_CLAIMED,
                            "buyer refund outcome unknown: " + uncertain.getMessage());
                    return;
                }
                transitionPropertyAsync(settlement.id(), PropertyMarketSettlementStore.REFUND_CLAIMED,
                        refunded ? PropertyMarketSettlementStore.COMPENSATED
                                : PropertyMarketSettlementStore.REFUND_READY,
                        refunded ? "" : "buyer refund rejected");
                if (refunded) sendBuyerMessage(settlement.buyerUuid(), "§c房屋转移失败，扣款已退回。");
            });
        }, () -> plugin.getLogger().warning("[Property settlement] Refund queue rejected: " + settlement.id()));
    }

    private void recoverPropertySettlements() {
        executeDatabaseOrReject(() -> {
            List<PropertyMarketSettlementStore.Settlement> open;
            int reviews;
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                PropertyMarketSettlementStore.initialize(connection);
                PropertyMarketSettlementStore.resetAtomicEnterprisePayoutClaims(connection, now());
                reviews = PropertyMarketSettlementStore.markUnknownWalletCallsForReview(connection, now());
                open = PropertyMarketSettlementStore.open(connection);
            } catch (SQLException failure) {
                plugin.getLogger().severe("[Property settlement] Startup recovery failed: " + failure.getMessage());
                return;
            }
            if (reviews > 0) plugin.getLogger().severe("[Property settlement] " + reviews
                    + " wallet outcomes require administrator review");
            for (PropertyMarketSettlementStore.Settlement settlement : open) {
                switch (settlement.status()) {
                    case PropertyMarketSettlementStore.BUYER_CHARGE_READY ->
                            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(),
                                    () -> processPropertyBuyerCharge(settlement));
                    case PropertyMarketSettlementStore.TRANSFER_READY -> processPropertyTransfer(settlement, false);
                    case PropertyMarketSettlementStore.TRANSFER_CLAIMED -> processPropertyTransfer(settlement, true);
                    case PropertyMarketSettlementStore.SELLER_PAYOUT_READY ->
                            plugin.scheduler().runEntityOrGlobal(settlement.sellerUuid(),
                                    () -> processPropertySellerPayout(settlement));
                    case PropertyMarketSettlementStore.REFUND_READY ->
                            plugin.scheduler().runEntityOrGlobal(settlement.buyerUuid(), () -> processPropertyRefund(settlement));
                    default -> { }
                }
            }
        }, () -> plugin.getLogger().warning("[Property settlement] Startup recovery queue rejected"));
    }

    private boolean transitionProperty(String id, String expected, String next, String error) {
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            return connection != null && PropertyMarketSettlementStore.transition(
                    connection, id, expected, next, error, now());
        } catch (SQLException failure) {
            plugin.getLogger().warning("[Property settlement] State update failed " + id + ": "
                    + failure.getMessage());
            return false;
        }
    }

    private void transitionPropertyAsync(String id, String expected, String next, String error) {
        executeDatabaseOrReject(() -> transitionProperty(id, expected, next, error),
                () -> plugin.getLogger().severe("[Property settlement] State update queue rejected: " + id));
    }

    private void markPropertyReview(String id, String expected, String error) {
        if (!transitionProperty(id, expected, PropertyMarketSettlementStore.REVIEW_REQUIRED, error)) {
            plugin.getLogger().severe("[Property settlement] Could not mark administrator review: " + id);
        }
    }

    private void markPropertyReviewAsync(String id, String expected, String error) {
        executeDatabaseOrReject(() -> markPropertyReview(id, expected, error),
                () -> plugin.getLogger().severe("[Property settlement] Review queue rejected: " + id));
    }

    PropertyReviewResolution resolvePropertyReview(String id, String action) throws SQLException {
        PropertyMarketSettlementStore.Settlement settlement;
        String nextAction = "";
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            PropertyMarketSettlementStore.initialize(connection);
            settlement = PropertyMarketSettlementStore.find(connection, id);
            if (settlement == null || !PropertyMarketSettlementStore.REVIEW_REQUIRED.equals(settlement.status())) {
                throw new SQLException("property settlement is not awaiting review");
            }
            String stage = settlement.reviewStage();
            switch (action) {
                case "CONFIRM_CHARGE_SUCCEEDED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.TRANSFER_READY,
                            "administrator confirmed buyer charge", now()));
                    nextAction = "TRANSFER";
                }
                case "CONFIRM_CHARGE_FAILED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            "administrator rejected buyer charge", now()));
                    requirePropertyResolution(PropertyMarketSettlementStore.compensateBeforeTransfer(connection,
                            settlement, PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            "administrator confirmed buyer charge failed", now()));
                }
                case "RECHECK_TRANSFER" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.TRANSFER_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.TRANSFER_CLAIMED,
                            "administrator requested ownership reconciliation", now()));
                    nextAction = "RECHECK_TRANSFER";
                }
                case "CONFIRM_PAYOUT_SUCCEEDED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.FINALIZED,
                            "administrator confirmed seller payout", now()));
                    nextAction = "RECORD_TAX";
                }
                case "CONFIRM_PAYOUT_FAILED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.SELLER_PAYOUT_READY,
                            "administrator confirmed seller payout failed", now()));
                    nextAction = "PAYOUT";
                }
                case "CONFIRM_REFUND_SUCCEEDED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.REFUND_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.COMPENSATED,
                            "administrator confirmed buyer refund", now()));
                }
                case "CONFIRM_REFUND_FAILED" -> {
                    requirePropertyStage(stage, PropertyMarketSettlementStore.REFUND_CLAIMED);
                    requirePropertyResolution(PropertyMarketSettlementStore.resolveReview(connection, id, stage,
                            PropertyMarketSettlementStore.REFUND_READY,
                            "administrator confirmed buyer refund failed", now()));
                    nextAction = "REFUND";
                }
                default -> throw new SQLException("unsupported property review action");
            }
            settlement = PropertyMarketSettlementStore.find(connection, id);
        }
        PropertyMarketSettlementStore.Settlement resolved = settlement;
        switch (nextAction) {
            case "TRANSFER" -> processPropertyTransfer(resolved, false);
            case "RECHECK_TRANSFER" -> processPropertyTransfer(resolved, true);
            case "PAYOUT" -> plugin.scheduler().runEntityOrGlobal(resolved.sellerUuid(),
                    () -> processPropertySellerPayout(resolved));
            case "REFUND" -> plugin.scheduler().runEntityOrGlobal(resolved.buyerUuid(),
                    () -> processPropertyRefund(resolved));
            case "RECORD_TAX" -> recordTax(resolved.buyerUuid().toString(), resolved.buyerName(),
                    "PROPERTY_TRADE", resolved.saleAmount(), resolved.taxRate(), resolved.taxAmount(),
                    "购买商品房 " + resolved.houseId());
            default -> { }
        }
        return new PropertyReviewResolution(resolved.id(), resolved.status());
    }

    private static void requirePropertyStage(String actual, String expected) throws SQLException {
        if (!expected.equals(actual)) throw new SQLException("property review stage does not allow this action");
    }

    private static void requirePropertyResolution(boolean resolved) throws SQLException {
        if (!resolved) throw new SQLException("property settlement review changed concurrently");
    }

    record PropertyReviewResolution(String id, String status) { }

    MarketReviewResolution resolveMarketReview(String id, String action) throws SQLException {
        MarketPurchaseSettlementStore.Settlement settlement;
        String nextAction = "";
        ListingManager.Listing listing = null;
        ListingManager.PurchaseReservation reservation = null;
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            MarketPurchaseSettlementStore.initialize(connection);
            settlement = MarketPurchaseSettlementStore.find(connection, id);
            if (settlement == null || !MarketPurchaseSettlementStore.REVIEW_REQUIRED.equals(settlement.status())) {
                throw new SQLException("market settlement is not awaiting review");
            }
            String stage = settlement.reviewStage();
            switch (action) {
                case "CONFIRM_CHARGE_SUCCEEDED" -> {
                    requireMarketStage(stage, MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED);
                    listing = plugin.listingManager().getListingForSettlement(settlement.listingId());
                    if (listing == null) throw new SQLException("listing is unavailable for charged purchase recovery");
                    requireMarketResolution(MarketPurchaseSettlementStore.resolveReview(connection, id, stage,
                            MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                            "administrator confirmed buyer charge", now()));
                    nextAction = "RESERVE";
                }
                case "CONFIRM_CHARGE_FAILED" -> {
                    requireMarketStage(stage, MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED);
                    requireMarketResolution(MarketPurchaseSettlementStore.resolveReview(connection, id, stage,
                            MarketPurchaseSettlementStore.COMPENSATED,
                            "administrator confirmed buyer charge failed", now()));
                }
                case "CONFIRM_PAYOUT_SUCCEEDED", "CONFIRM_PAYOUT_FAILED" -> {
                    requireMarketStage(stage, MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED);
                    listing = plugin.listingManager().getListingForSettlement(settlement.listingId());
                    if (listing == null || settlement.storageId() == null) {
                        throw new SQLException("listing reservation is unavailable for payout recovery");
                    }
                    reservation = new ListingManager.PurchaseReservation(settlement.storageId(),
                            settlement.listingId(), settlement.buyerUuid(), settlement.quantity(),
                            ListingManager.DEFAULT_CURRENCY_ID, settlement.totalCost());
                    requireMarketResolution(MarketPurchaseSettlementStore.resolveReview(connection, id, stage,
                            MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED,
                            "administrator resolved seller payout", now()));
                    nextAction = "CONFIRM_PAYOUT_SUCCEEDED".equals(action) ? "FINALIZE" : "ROLLBACK";
                }
                case "CONFIRM_REFUND_SUCCEEDED" -> {
                    requireMarketStage(stage, MarketPurchaseSettlementStore.REFUND_CLAIMED);
                    requireMarketResolution(MarketPurchaseSettlementStore.resolveReview(connection, id, stage,
                            MarketPurchaseSettlementStore.COMPENSATED,
                            "administrator confirmed buyer refund", now()));
                }
                case "CONFIRM_REFUND_FAILED" -> {
                    requireMarketStage(stage, MarketPurchaseSettlementStore.REFUND_CLAIMED);
                    requireMarketResolution(MarketPurchaseSettlementStore.resolveReview(connection, id, stage,
                            MarketPurchaseSettlementStore.REFUND_READY,
                            "administrator confirmed buyer refund failed", now()));
                    nextAction = "REFUND";
                }
                default -> throw new SQLException("unsupported market review action");
            }
            settlement = MarketPurchaseSettlementStore.find(connection, id);
        }
        MarketPurchaseSettlementStore.Settlement resolved = settlement;
        ListingManager.Listing resolvedListing = listing;
        ListingManager.PurchaseReservation resolvedReservation = reservation;
        switch (nextAction) {
            case "RESERVE" -> reserveChargedPurchase(resolved, resolvedListing);
            case "FINALIZE" -> finalizeMarketPurchase(resolved, resolvedListing, resolvedReservation);
            case "ROLLBACK" -> rollbackMarketReservation(resolved, resolvedReservation,
                    MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED,
                    "administrator confirmed seller payout failed");
            case "REFUND" -> plugin.scheduler().runEntityOrGlobal(resolved.buyerUuid(), () -> refundMarketBuyer(resolved));
            default -> { }
        }
        return new MarketReviewResolution(resolved.id(), resolved.status());
    }

    private static void requireMarketStage(String actual, String expected) throws SQLException {
        if (!expected.equals(actual)) throw new SQLException("market review stage does not allow this action");
    }

    private static void requireMarketResolution(boolean resolved) throws SQLException {
        if (!resolved) throw new SQLException("market settlement review changed concurrently");
    }

    record MarketReviewResolution(String id, String status) { }

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

    /** 玩家上架个人产权商品房；企业房产在企业公户结算接线前禁止进入个人钱包市场路径。 */
    public boolean listHouseForSale(Player seller, String houseId, double price) {
        if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) {
            seller.sendMessage("§c价格必须是有效正数。");
            return false;
        }
        UUID sellerUuid = seller.getUniqueId();
        String sellerName = seller.getName();
        String formattedPrice = plugin.vaultHook().format(price);
        return executeDatabaseOrReject(() -> {
            Object houseObj = callRealEstate("getHouse", new Class<?>[]{String.class}, houseId);
            if (!(houseObj instanceof Map<?, ?> house)) {
                sendSellerMessage(sellerUuid, "§c房屋不存在，或房地产模块未加载。");
                return;
            }
            String ownerType = String.valueOf(house.get("ownerType"));
            String ownerId = String.valueOf(house.get("ownerId"));
            boolean enterpriseAuthorized = !"ENTERPRISE".equalsIgnoreCase(ownerType)
                    || isEnterprisePropertySellerAuthorized(ownerId, sellerUuid);
            PropertySalePolicy.Decision decision = PropertySalePolicy.evaluate(
                    ownerType, ownerId, sellerUuid, enterpriseAuthorized);
            if (!decision.allowed()) {
                sendSellerMessage(sellerUuid,
                        decision.reason() == PropertySalePolicy.Reason.ENTERPRISE_NOT_AUTHORIZED
                                ? "§c你没有该企业的房产挂牌权限。"
                                : "§c你不是该房屋的所有者，或产权信息无效。");
                return;
            }
            var listing = plugin.listingManager().createPropertyListing(
                    sellerUuid, sellerName, houseId, price);
            sendSellerMessage(sellerUuid, listing == null
                    ? "§c该房屋已有在售挂牌，或上架失败。"
                    : "§a已上架商品房 " + houseId + "，价格: " + formattedPrice);
        }, () -> sendSellerMessage(sellerUuid, "§c结算队列繁忙，请稍后重试。"));
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
            plugin.asyncWorkPool().executeDatabase(() -> plugin.priceEngine().recordTrades(completedTrades));
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
        executeDatabaseOrReject(() -> {
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_records (id,payer_uuid, payer_name, category, " +
                        "base_amount, tax_rate, tax_amount, description, collected_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, "TAX-" + UUID.randomUUID());
                    ps.setString(2, payerUuid);
                    ps.setString(3, payerName);
                    ps.setString(4, category);
                    ps.setDouble(5, baseAmount);
                    ps.setDouble(6, taxRate);
                    ps.setDouble(7, taxAmount);
                    ps.setString(8, description);
                    ps.setLong(9, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("记录税收失败: " + e.getMessage());
            }
        }, () -> plugin.getLogger().warning("记录税收排队失败: " + category));
    }
}
