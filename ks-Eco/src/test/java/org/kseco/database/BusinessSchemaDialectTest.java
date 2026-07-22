package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessSchemaDialectTest {

    @Test
    void generatesVendorSpecificTypesAndIndexSyntax() {
        assertEquals("DOUBLE PRECISION", BusinessSchemaDialect.floatingPointType(DatabaseDialect.POSTGRESQL));
        assertEquals("DOUBLE", BusinessSchemaDialect.floatingPointType(DatabaseDialect.MYSQL));
        assertEquals("BYTEA", BusinessSchemaDialect.binaryType(DatabaseDialect.POSTGRESQL));
        assertEquals("BLOB", BusinessSchemaDialect.binaryType(DatabaseDialect.MARIADB));
        assertEquals("INTEGER PRIMARY KEY AUTOINCREMENT",
                BusinessSchemaDialect.identityPrimaryKey(DatabaseDialect.SQLITE));
        assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY",
                BusinessSchemaDialect.identityPrimaryKey(DatabaseDialect.MYSQL));
        assertEquals("BIGSERIAL PRIMARY KEY",
                BusinessSchemaDialect.identityPrimaryKey(DatabaseDialect.POSTGRESQL));

        String mysql = BusinessSchemaDialect.createIndexSql(DatabaseDialect.MYSQL,
                "idx_status", "settlements", "status", "updated_at");
        String postgres = BusinessSchemaDialect.createIndexSql(DatabaseDialect.POSTGRESQL,
                "idx_status", "settlements", "status", "updated_at");
        String uniqueMysql = BusinessSchemaDialect.createUniqueIndexSql(DatabaseDialect.MYSQL,
                "uq_active", "settlements", "active_id");
        assertFalse(mysql.contains("IF NOT EXISTS"));
        assertTrue(postgres.contains("IF NOT EXISTS"));
        assertEquals("CREATE UNIQUE INDEX uq_active ON settlements(active_id)", uniqueMysql);
    }

    @Test
    void sqliteSchemaOperationsAreIdempotent() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE settlements (id VARCHAR(32) PRIMARY KEY,status VARCHAR(16))");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_status", "settlements", "status");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_status", "settlements", "status");
            BusinessSchemaDialect.addColumnIfMissing(connection, "settlements", "review_stage",
                    "VARCHAR(32) NOT NULL DEFAULT ''");
            BusinessSchemaDialect.createUniqueIndexIfMissing(connection, "uq_review_stage",
                    "settlements", "review_stage");
            BusinessSchemaDialect.createUniqueIndexIfMissing(connection, "uq_review_stage",
                    "settlements", "review_stage");
            BusinessSchemaDialect.addColumnIfMissing(connection, "settlements", "review_stage",
                    "VARCHAR(32) NOT NULL DEFAULT ''");

            try (var rows = connection.createStatement().executeQuery("PRAGMA table_info(settlements)")) {
                int matches = 0;
                while (rows.next()) if ("review_stage".equals(rows.getString("name"))) matches++;
                assertEquals(1, matches);
            }
        }
    }
}
