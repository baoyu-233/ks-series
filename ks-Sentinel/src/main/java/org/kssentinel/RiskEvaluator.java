package org.kssentinel;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 纯逻辑：解析指令文本，匹配排除/高危规则，判定风险等级。
 * 不依赖 Bukkit 事件对象，便于独立测试。
 */
public final class RiskEvaluator {

    private RiskEvaluator() {}

    public record Rule(int id, String commandPrefix, boolean checkTargetArg, String riskLevel, boolean enabled) {}

    public record Exclusion(int id, String commandPrefix) {}

    public record Result(boolean excluded, String baseCommand, String riskLevel, String targetName, UUID targetUuid) {}

    /**
     * @param fullCommand   不含前导 "/" 的完整指令（如 "give Foo diamond 1"）
     * @param executorName  执行者名称（控制台传 "CONSOLE"）
     * @param rules         高危规则表（仅 enabled 的会生效）
     * @param exclusions    排除规则表
     * @param onlinePlayerLookup 按名称（忽略大小写）查在线玩家 UUID，找不到返回 null；用于判定指令参数中是否引用了"别人"
     */
    public static Result evaluate(String fullCommand, String executorName,
                                   List<Rule> rules, List<Exclusion> exclusions,
                                   Function<String, UUID> onlinePlayerLookup) {
        String trimmed = fullCommand == null ? "" : fullCommand.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return new Result(true, "", "INFO", null, null);
        }

        String base = normalizeBase(tokens[0]);

        for (Exclusion ex : exclusions) {
            if (base.equalsIgnoreCase(normalizeBase(ex.commandPrefix()))) {
                return new Result(true, base, "INFO", null, null);
            }
        }

        Rule matched = null;
        for (Rule r : rules) {
            if (!r.enabled()) continue;
            if (base.equalsIgnoreCase(normalizeBase(r.commandPrefix()))) {
                matched = r;
                break;
            }
        }

        if (matched == null) {
            return new Result(false, base, "INFO", null, null);
        }

        if (!matched.checkTargetArg()) {
            return new Result(false, base, matched.riskLevel(), null, null);
        }

        // 在参数里找一个解析为"别人"的在线玩家名
        for (int i = 1; i < tokens.length; i++) {
            String arg = tokens[i];
            if (arg.isEmpty() || arg.startsWith("@") || arg.startsWith("-")) continue;
            if (arg.equalsIgnoreCase(executorName)) continue;
            UUID uuid = onlinePlayerLookup.apply(arg);
            if (uuid != null) {
                return new Result(false, base, matched.riskLevel(), arg, uuid);
            }
        }

        // 命中规则但没解析出"别人"目标（多半是对自己操作）——降级为 INFO
        return new Result(false, base, "INFO", null, null);
    }

    /** "minecraft:give" → "give"；统一小写。 */
    private static String normalizeBase(String token) {
        String t = token.toLowerCase(java.util.Locale.ROOT);
        int idx = t.indexOf(':');
        return idx >= 0 ? t.substring(idx + 1) : t;
    }
}
