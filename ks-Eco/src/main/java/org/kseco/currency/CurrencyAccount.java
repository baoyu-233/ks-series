package org.kseco.currency;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable account identity. It deliberately contains no Bukkit object. */
public record CurrencyAccount(String type, String id) {
    private static final Pattern VALID_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public CurrencyAccount {
        Objects.requireNonNull(type, "type");
        type = type.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid account type: " + type);
        }
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isEmpty() || id.length() > 128 || containsControlCharacter(id)) {
            throw new IllegalArgumentException("Account id must contain 1-128 printable characters");
        }
    }

    public static CurrencyAccount player(UUID playerId) {
        return new CurrencyAccount("PLAYER", Objects.requireNonNull(playerId, "playerId").toString());
    }

    public static CurrencyAccount system(String id) {
        return new CurrencyAccount("SYSTEM", id);
    }

    public static CurrencyAccount enterprise(String id) {
        return new CurrencyAccount("ENTERPRISE", id);
    }

    public static CurrencyAccount bank(String id) {
        return new CurrencyAccount("BANK", id);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }
}
