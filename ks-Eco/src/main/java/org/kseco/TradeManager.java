package org.kseco;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * 玩家间交易管理器。
 * 支持 1v1 交易（双方放入物品+货币，确认后交换）。
 */
public final class TradeManager {

    private final KsEco plugin;
    /** 活跃的交易会话：targetUuid → TradeSession */
    private final Map<UUID, TradeSession> sessions = new HashMap<>();

    public TradeManager(KsEco plugin) {
        this.plugin = plugin;
    }

    /**
     * 发起交易请求。
     */
    public boolean requestTrade(Player sender, Player target) {
        if (sessions.containsKey(target.getUniqueId())) {
            sender.sendMessage("§c该玩家已在交易中。");
            return false;
        }

        TradeSession session = new TradeSession(sender, target);
        sessions.put(target.getUniqueId(), session);
        sessions.put(sender.getUniqueId(), session);

        target.sendMessage("§e" + sender.getName() + " §a向你发起了交易请求！输入 §e/trade " + sender.getName() + " §a接受。");
        sender.sendMessage("§a已向 " + target.getName() + " 发送交易请求。");
        return true;
    }

    /**
     * 获取玩家的活跃交易会话。
     */
    public TradeSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /**
     * 添加物品到交易窗口（己方）。
     */
    public boolean addItem(Player player, ItemStack item) {
        TradeSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;

        session.addItem(player, item);
        return true;
    }

    /**
     * 添加货币到交易窗口（己方）。
     */
    public boolean addMoney(Player player, double amount) {
        TradeSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;

        if (!plugin.vaultHook().has(player, amount)) {
            player.sendMessage("§c余额不足。");
            return false;
        }

        // 先扣款暂存
        plugin.vaultHook().withdraw(player, amount);
        session.addMoney(player, amount);
        return true;
    }

    /**
     * 确认交易。
     */
    public boolean confirm(Player player) {
        TradeSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;

        session.confirm(player);

        if (session.bothConfirmed()) {
            executeTrade(session);
            return true;
        }
        return false;
    }

    /**
     * 取消交易（归还物品和货币）。
     */
    public void cancel(Player player) {
        TradeSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;

        sessions.remove(session.sender.getUniqueId());
        sessions.remove(session.target.getUniqueId());

        // 归还物品
        for (var entry : session.senderItems.entrySet()) {
            session.sender.getInventory().addItem(entry.getValue());
        }
        for (var entry : session.targetItems.entrySet()) {
            session.target.getInventory().addItem(entry.getValue());
        }
        session.sender.sendMessage("§c交易已取消。");
        session.target.sendMessage("§c交易已取消。");
    }

    private void executeTrade(TradeSession session) {
        sessions.remove(session.sender.getUniqueId());
        sessions.remove(session.target.getUniqueId());

        // 交换物品
        for (ItemStack item : session.targetItems.values()) {
            session.sender.getInventory().addItem(item);
        }
        for (ItemStack item : session.senderItems.values()) {
            session.target.getInventory().addItem(item);
        }

        // 交换货币
        if (session.senderMoney > 0) {
            plugin.vaultHook().deposit(session.target, session.senderMoney);
        }
        if (session.targetMoney > 0) {
            plugin.vaultHook().deposit(session.sender, session.targetMoney);
        }

        session.sender.sendMessage("§a交易完成！");
        session.target.sendMessage("§a交易完成！");
    }

    // ---- 内部类 ----

    public static class TradeSession {
        public final Player sender;
        public final Player target;
        public final Map<Integer, ItemStack> senderItems = new LinkedHashMap<>();
        public final Map<Integer, ItemStack> targetItems = new LinkedHashMap<>();
        public double senderMoney = 0;
        public double targetMoney = 0;
        private boolean senderConfirmed = false;
        private boolean targetConfirmed = false;

        TradeSession(Player sender, Player target) {
            this.sender = sender;
            this.target = target;
        }

        void addItem(Player player, ItemStack item) {
            Map<Integer, ItemStack> items = player.equals(sender) ? senderItems : targetItems;
            int slot = items.size();
            items.put(slot, item.clone());
        }

        void addMoney(Player player, double amount) {
            if (player.equals(sender)) senderMoney += amount;
            else targetMoney += amount;
        }

        void confirm(Player player) {
            if (player.equals(sender)) senderConfirmed = true;
            else targetConfirmed = true;
        }

        boolean bothConfirmed() {
            return senderConfirmed && targetConfirmed;
        }
    }
}
