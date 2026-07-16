package org.itemedit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 临时聊天监听——替代铁砧输入，完全不依赖 Paper 的 AnvilView API。
 *
 * 原理：
 * 1. prompt() 提示玩家在聊天栏输入文字
 * 2. 注册一个临时 AsyncPlayerChatEvent 监听器（HIGHEST 优先，抢在其他插件前拿到原始消息）
 * 3. 玩家发送消息 → 捕获 → 取消事件（不显式广播）→ 回调 → 注销
 * 4. 玩家输入 "cancel" 或离线 → 取消 → 回调
 *
 * 因为监听器只在输入期间存在（通常 < 30 秒），且以 LOWEST 优先级抢占，
 * 所以对其他聊天插件的干扰降到最低。
 */
public final class ChatInput implements Listener {

    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();

    private static class Session {
        final Consumer<String> onConfirm;
        final Runnable onCancel;
        final UUID playerId; // for scheduling

        Session(Consumer<String> onConfirm, Runnable onCancel, UUID playerId) {
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.playerId = playerId;
        }
    }

    private static ChatInput instance;
    private boolean registered = false;

    private ChatInput() {}

    /** 注册到 Bukkit（由 ItemEditor.onEnable 调用一次）。 */
    public static void init() {
        if (instance == null) {
            instance = new ChatInput();
            Bukkit.getPluginManager().registerEvents(instance,
                    Bukkit.getPluginManager().getPlugin("KS-ItemEditor"));
            instance.registered = true;
        }
    }

    /** 提示玩家在聊天栏输入文字。会话超时约 60 秒。 */
    public static void prompt(Player player, String promptTitle,
                              String initial,
                              Consumer<String> onConfirm,
                              Runnable onCancel) {
        player.sendMessage("");
        player.sendMessage(TextUtil.parse("&6▎ &e" + promptTitle));
        if (initial != null && !initial.isEmpty()) {
            player.sendMessage(TextUtil.parse("&7  当前值: &f" + initial));
        }
        player.sendMessage(TextUtil.parse("&7  在聊天栏输入新内容，输入 &ccancel &7取消"));
        player.sendMessage("");

        // 关闭当前 GUI，让玩家回到正常界面方便输入
        player.closeInventory();

        ACTIVE.put(player.getUniqueId(), new Session(onConfirm, onCancel, player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Session session = ACTIVE.remove(event.getPlayer().getUniqueId());
        if (session == null) return;

        // 抢占消息——取消事件，不让消息出现在公屏，也不让其他插件处理
        event.setCancelled(true);
        String message = event.getMessage().trim();

        if (message.equalsIgnoreCase("cancel")) {
            // 在主线程回调 cancel
            schedule(() -> session.onCancel.run());
        } else {
            // 在主线程回调 confirm
            schedule(() -> session.onConfirm.accept(message));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = ACTIVE.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            schedule(() -> session.onCancel.run());
        }
    }

    private static void schedule(Runnable task) {
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("KS-ItemEditor"), task);
    }
}
