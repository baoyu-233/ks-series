package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Randomly acquires market listings priced below the protected market floor. */
public final class OfficialMarketSweepManager {
    private final KsEco plugin;
    private final AtomicBoolean sweepRunning = new AtomicBoolean();
    private final Set<String> evaluationsInFlight = ConcurrentHashMap.newKeySet();

    public OfficialMarketSweepManager(KsEco plugin) {
        this.plugin = plugin;
    }

    public void runSweep() {
        if (!plugin.ecoConfig().isOfficialSweepEnabled()
                || !plugin.ecoConfig().isMarketProtectionEnabled()) return;
        if (!sweepRunning.compareAndSet(false, true)) return;
        int sampleSize = plugin.ecoConfig().getOfficialSweepSampleSize();
        plugin.asyncWorkPool().executeDatabase(() -> {
            try {
                List<ListingManager.Listing> listings = new ArrayList<>(plugin.listingManager().getActiveListings("SELL", null));
                Collections.shuffle(listings);
                int limit = Math.min(sampleSize, listings.size());
                List<ListingManager.Listing> sample = List.copyOf(listings.subList(0, limit));
                if (sample.isEmpty()) {
                    sweepRunning.set(false);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> processNext(sample, 0,
                        plugin.marketValueService().newMarketFloorSession()));
            } catch (RuntimeException exception) {
                sweepRunning.set(false);
                plugin.getLogger().warning("Official market sweep failed: " + exception.getMessage());
            }
        });
    }

    /** Queue a protection check immediately after a normal item listing is committed. */
    public void evaluateNewListing(ListingManager.Listing listing) {
        if (!isProtectionEnabled() || !isEligible(listing)) return;
        evaluateLatestAsync(listing.id(), null, null);
    }

    private void processNext(List<ListingManager.Listing> sample, int index,
                             MarketValueService.MarketFloorSession valuationSession) {
        if (!plugin.isEnabled()) {
            sweepRunning.set(false);
            return;
        }
        evaluateLatestAsync(sample.get(index).id(), valuationSession,
                () -> scheduleNext(sample, index + 1, valuationSession));
    }

    private void scheduleNext(List<ListingManager.Listing> sample, int next,
                              MarketValueService.MarketFloorSession valuationSession) {
        if (next >= sample.size()) {
            sweepRunning.set(false);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> processNext(sample, next, valuationSession), 1L);
    }

    /**
     * Re-read the ACTIVE row off-thread before every decision. The ID set prevents an immediate
     * check and a periodic sweep from evaluating the same listing concurrently. Bukkit item
     * decoding, recipe access, Vault and inventory-related work remain on the server thread.
     */
    private void evaluateLatestAsync(String listingId,
                                     MarketValueService.MarketFloorSession valuationSession,
                                     Runnable completion) {
        if (!evaluationsInFlight.add(listingId)) {
            if (completion != null) completion.run();
            return;
        }
        plugin.asyncWorkPool().executeDatabase(() -> {
            ListingManager.Listing latest = null;
            RuntimeException loadFailure = null;
            try {
                latest = plugin.listingManager().getListing(listingId);
            } catch (RuntimeException exception) {
                loadFailure = exception;
            }
            if (!plugin.isEnabled()) {
                evaluationsInFlight.remove(listingId);
                sweepRunning.set(false);
                return;
            }
            ListingManager.Listing loadedListing = latest;
            RuntimeException failure = loadFailure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure != null) {
                    plugin.getLogger().warning("Could not load market listing during official sweep: "
                            + failure.getMessage());
                    finishEvaluation(listingId, completion);
                    return;
                }
                prepareAndEvaluate(loadedListing, valuationSession, listingId, completion);
            });
        });
    }

    private void prepareAndEvaluate(ListingManager.Listing listing,
                                    MarketValueService.MarketFloorSession valuationSession,
                                    String listingId, Runnable completion) {
        if (listing == null || !isProtectionEnabled() || !isEligible(listing)) {
            finishEvaluation(listingId, completion);
            return;
        }
        ItemStack item;
        MarketValueService.PreparedItem prepared;
        MarketValueService.MarketFloorSession session;
        try {
            item = listing.toItemStack();
            if (item == null || item.getType().isAir()) {
                finishEvaluation(listingId, completion);
                return;
            }
            prepared = plugin.marketValueService().prepareMarketItem(item);
            session = valuationSession != null
                    ? valuationSession : plugin.marketValueService().newMarketFloorSession();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not prepare market listing during official sweep: "
                    + exception.getMessage());
            finishEvaluation(listingId, completion);
            return;
        }
        plugin.asyncWorkPool().executeDatabase(() -> {
            double protectedUnitPrice;
            OfficialWarehouseManager.Acquisition acquisition = null;
            RuntimeException valuationFailure = null;
            try {
                protectedUnitPrice = session.unitValue(prepared);
                if (Double.isFinite(protectedUnitPrice) && protectedUnitPrice > 0.0
                        && listing.unitPrice() < protectedUnitPrice) {
                    acquisition = plugin.officialWarehouseManager()
                            .claimListing(listing, protectedUnitPrice);
                }
            } catch (RuntimeException exception) {
                protectedUnitPrice = 0.0;
                valuationFailure = exception;
            }
            if (!plugin.isEnabled()) {
                evaluationsInFlight.remove(listingId);
                sweepRunning.set(false);
                return;
            }
            OfficialWarehouseManager.Acquisition claimed = acquisition;
            RuntimeException failure = valuationFailure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (failure != null) throw failure;
                    if (claimed != null) settleAcquisition(claimed);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Could not evaluate market listing during official sweep: "
                            + exception.getMessage());
                } finally {
                    finishEvaluation(listingId, completion);
                }
            });
        });
    }

    private void finishEvaluation(String listingId, Runnable completion) {
        evaluationsInFlight.remove(listingId);
        if (completion != null) completion.run();
    }

    private boolean isProtectionEnabled() {
        return plugin.ecoConfig().isOfficialSweepEnabled()
                && plugin.ecoConfig().isMarketProtectionEnabled();
    }

    private boolean isEligible(ListingManager.Listing listing) {
        return listing != null && !listing.isProperty() && !listing.isBarter()
                && "SELL".equals(listing.listingType()) && listing.quantity() > 0;
    }

    private void settleAcquisition(OfficialWarehouseManager.Acquisition acquisition) {
        var seller = Bukkit.getOfflinePlayer(acquisition.sellerUuid());
        if (!plugin.vaultHook().deposit(seller, acquisition.payment())) {
            plugin.asyncWorkPool().executeDatabase(() -> {
                if (!plugin.officialWarehouseManager().rollbackAcquisition(acquisition)) {
                    plugin.getLogger().severe("Could not roll back unpaid official acquisition: "
                            + acquisition.listingId());
                }
            });
            return;
        }
        plugin.asyncWorkPool().executeDatabase(() -> plugin.priceEngine().recordTrade(
                acquisition.material(), acquisition.quantity(), acquisition.unitPrice(), "OFFICIAL",
                acquisition.sellerUuid().toString(), "OFFICIAL_MARKET_SWEEP"));
        var onlineSeller = Bukkit.getPlayer(acquisition.sellerUuid());
        if (onlineSeller != null) {
            onlineSeller.sendMessage("§a你的低价挂单已由官方收购，收入: "
                    + plugin.vaultHook().format(acquisition.payment()) + "。物品已进入官方暂存仓。");
        }
    }
}
