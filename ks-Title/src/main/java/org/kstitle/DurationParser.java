package org.kstitle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter EXPIRE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private DurationParser() {}

    public static long parseMillis(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("duration is empty");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = PART.matcher(value);
        long total = 0L;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException("bad duration: " + raw);
            long amount = Long.parseLong(matcher.group(1));
            total = Math.addExact(total, Math.multiplyExact(amount, unitMillis(matcher.group(2).charAt(0))));
            end = matcher.end();
        }
        if (end != value.length() || total <= 0L) throw new IllegalArgumentException("bad duration: " + raw);
        return total;
    }

    public static String formatDuration(long millis) {
        if (millis <= 0L) return "0s";
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder out = new StringBuilder();
        append(out, days, "d");
        append(out, hours, "h");
        append(out, minutes, "m");
        if (out.length() == 0 || seconds > 0L) append(out, seconds, "s");
        return out.toString().trim();
    }

    public static String formatExpiry(long expiresAt) {
        if (expiresAt <= 0L) return "permanent";
        return EXPIRE_TIME.format(Instant.ofEpochMilli(expiresAt));
    }

    private static long unitMillis(char unit) {
        return switch (Character.toLowerCase(unit)) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> throw new IllegalArgumentException("bad unit: " + unit);
        };
    }

    private static void append(StringBuilder out, long value, String unit) {
        if (value <= 0L) return;
        if (out.length() > 0) out.append(' ');
        out.append(value).append(unit);
    }
}
