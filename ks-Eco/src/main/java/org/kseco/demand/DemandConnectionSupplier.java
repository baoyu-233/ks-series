package org.kseco.demand;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface DemandConnectionSupplier {
    Connection openConnection() throws SQLException;
}
