package org.kseco.currency;

import java.sql.Connection;
import java.sql.SQLException;

/** Compatible with {@code plugin.ecoDatabase()::openConnection}. */
@FunctionalInterface
public interface CurrencyConnectionSupplier {
    Connection openConnection() throws SQLException;
}
