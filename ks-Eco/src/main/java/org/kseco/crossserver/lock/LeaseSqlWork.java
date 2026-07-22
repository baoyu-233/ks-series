package org.kseco.crossserver.lock;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface LeaseSqlWork<T> {
    T execute(Connection connection) throws SQLException;
}
