package org.ksskill;

import org.bukkit.entity.Player;

/** Keeps automatic legacy skill triggers limited to real players. */
final class PlayerEligibility {

    private static final String LEAVES_BOT_INTERFACE = "org.leavesmc.leaves.entity.bot.Bot";

    private PlayerEligibility() {
    }

    static boolean isEligible(Player player) {
        return player != null && !isLeavesBotType(player.getClass());
    }

    static boolean isLeavesBotType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if ("org.leavesmc.leaves.bot.ServerBot".equals(name)
                || name.startsWith("org.leavesmc.leaves.bot.")
                || name.startsWith("org.leavesmc.leaves.entity.bot.")) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (isLeavesBotInterface(implemented)) return true;
            }
        }
        return false;
    }

    private static boolean isLeavesBotInterface(Class<?> type) {
        if (LEAVES_BOT_INTERFACE.equals(type.getName())) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (isLeavesBotInterface(parent)) return true;
        }
        return false;
    }
}
