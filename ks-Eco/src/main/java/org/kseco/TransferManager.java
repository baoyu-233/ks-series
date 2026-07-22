package org.kseco;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/** Player-to-player money transfers with a configurable tax-free allowance. */
public final class TransferManager {
    private static final String TAX_CATEGORY = "PLAYER_TRANSFER";
    private static final double MAX_TRANSACTION_AMOUNT = 1_000_000_000_000d;

    private final KsEco plugin;

    public TransferManager(KsEco plugin) {
        this.plugin = plugin;
    }

    public TransferQuote quote(double amount) {
        double allowance = Math.max(0.0, plugin.getConfig().getDouble("transfer.tax-free-amount", 1000.0));
        double taxableAmount = Math.max(0.0, amount - allowance);
        double rate = Math.max(0.0, Math.min(1.0,
                plugin.getCategoryTaxRate(TAX_CATEGORY,
                        plugin.getConfig().getDouble("transfer.tax-rate-fallback", 0.01))));
        double tax = taxableAmount <= 0.0 || rate <= 0.0
                ? 0.0
                : Math.max(taxableAmount * rate, plugin.ecoConfig().getMinTax());
        return new TransferQuote(amount, Math.min(amount, allowance), taxableAmount, rate, tax, amount + tax);
    }

    /** Must be called on the server thread because Vault providers are not generally thread-safe. */
    public TransferResult transfer(Player sender, OfflinePlayer recipient, double amount) {
        if (!plugin.isFeatureOpen(sender, "transfer")) {
            return TransferResult.failed("转账功能当前未开放。");
        }
        if (!plugin.vaultHook().isAvailable()) {
            return TransferResult.failed("经济系统尚未就绪。");
        }
        if (recipient == null || recipient.getUniqueId().equals(sender.getUniqueId())) {
            return TransferResult.failed("不能向自己转账。");
        }
        if (!Double.isFinite(amount) || amount <= 0.0 || amount > MAX_TRANSACTION_AMOUNT) {
            return TransferResult.failed("转账金额无效。");
        }

        TransferQuote quote = quote(amount);
        if (!Double.isFinite(quote.totalDebit()) || quote.totalDebit() > MAX_TRANSACTION_AMOUNT) {
            return TransferResult.failed("转账金额与税费合计超过系统上限。");
        }
        if (!plugin.vaultHook().has(sender, quote.totalDebit())) {
            return TransferResult.failed("余额不足，需要 " + plugin.vaultHook().format(quote.totalDebit()) + "（含税）。");
        }
        if (!plugin.vaultHook().withdraw(sender, quote.totalDebit())) {
            return TransferResult.failed("付款失败，请稍后重试。");
        }
        if (!plugin.vaultHook().deposit(recipient, amount)) {
            if (!plugin.vaultHook().deposit(sender, quote.totalDebit())) {
                plugin.getLogger().severe("[转账] 收款失败且退款失败: sender=" + sender.getUniqueId()
                        + " recipient=" + recipient.getUniqueId() + " amount=" + amount);
                return TransferResult.failed("收款失败且自动退款异常，请立即联系管理员。");
            }
            return TransferResult.failed("收款失败，款项已退回。");
        }

        if (quote.tax() > 0.0) recordTax(sender, recipient, quote);
        plugin.getLogger().info("[转账] " + sender.getName() + " -> "
                + (recipient.getName() == null ? recipient.getUniqueId() : recipient.getName())
                + " amount=" + amount + " tax=" + quote.tax());
        return TransferResult.success(quote);
    }

    private void recordTax(Player sender, OfflinePlayer recipient, TransferQuote quote) {
        String senderUuid = sender.getUniqueId().toString();
        String senderName = sender.getName();
        String recipientName = recipient.getName() == null ? recipient.getUniqueId().toString() : recipient.getName();
        plugin.asyncWorkPool().executeDatabase(() -> {
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_records (id,payer_uuid, payer_name, category, base_amount, "
                                + "tax_rate, tax_amount, description, collected_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, "TAX-" + UUID.randomUUID());
                    ps.setString(2, senderUuid);
                    ps.setString(3, senderName);
                    ps.setString(4, TAX_CATEGORY);
                    ps.setDouble(5, quote.taxableAmount());
                    ps.setDouble(6, quote.taxRate());
                    ps.setDouble(7, quote.tax());
                    ps.setString(8, "向 " + recipientName
                            + " 转账 " + quote.amount() + "（免税额 " + quote.exemptAmount() + "）");
                    ps.setLong(9, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[转账] 税收记录写入失败: " + e.getMessage());
            }
        });
    }

    public record TransferQuote(double amount, double exemptAmount, double taxableAmount,
                                double taxRate, double tax, double totalDebit) {}

    public record TransferResult(boolean success, String error, TransferQuote quote) {
        static TransferResult success(TransferQuote quote) {
            return new TransferResult(true, null, quote);
        }

        static TransferResult failed(String error) {
            return new TransferResult(false, error, null);
        }
    }
}
