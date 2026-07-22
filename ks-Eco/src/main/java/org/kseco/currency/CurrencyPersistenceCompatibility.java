package org.kseco.currency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/** Portable compatibility migrations for legacy currency-aware business rows. */
public final class CurrencyPersistenceCompatibility {
    private static final Pattern SAFE_TABLE = Pattern.compile("ks_[a-z0-9_]+");
    private static final String CASH = "CASH";

    private CurrencyPersistenceCompatibility() {
    }

    public static void canonicalizeLegacyCashRows(Connection connection, String tableName) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableName, "tableName");
        if (!SAFE_TABLE.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Unsafe currency migration table: " + tableName);
        }
        String sql = "UPDATE " + tableName + " SET currency_id=? "
                + "WHERE currency_id IS NULL OR TRIM(currency_id)='' OR UPPER(TRIM(currency_id))=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, CASH);
            statement.setString(2, CASH);
            statement.executeUpdate();
        }
    }
}
