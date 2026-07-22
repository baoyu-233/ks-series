package org.kseco.extra.realestate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class RealEstatePricingSchema {

    private static final String TABLE = "ks_re_zones";

    private RealEstatePricingSchema() {
    }

    static void migrate(Connection connection) throws SQLException {
        Set<String> columns = columns(connection);
        addColumn(connection, columns, "pricing_mode", "VARCHAR(16) NOT NULL DEFAULT 'FLAT'");
        addColumn(connection, columns, "price_per_block", "DOUBLE PRECISION NOT NULL DEFAULT 0");
        addColumn(connection, columns, "min_plot_price", "DOUBLE PRECISION NOT NULL DEFAULT 0");
        addColumn(connection, columns, "max_plot_area", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, columns, "player_soft_area", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, columns, "player_hard_area", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, columns, "enterprise_soft_area", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, columns, "enterprise_hard_area", "BIGINT NOT NULL DEFAULT 0");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + TABLE + " SET pricing_mode='FLAT' "
                    + "WHERE pricing_mode IS NULL OR UPPER(pricing_mode) NOT IN ('FLAT','PER_BLOCK')");
            statement.executeUpdate("UPDATE " + TABLE + " SET pricing_mode=UPPER(pricing_mode)");
        }
    }

    private static Set<String> columns(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE 1=0")) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                names.add(metadata.getColumnName(index).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static void addColumn(Connection connection, Set<String> columns,
                                  String name, String definition) throws SQLException {
        if (columns.contains(name)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + definition);
        }
        columns.add(name);
    }
}
