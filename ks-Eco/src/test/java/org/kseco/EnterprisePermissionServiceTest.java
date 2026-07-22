package org.kseco;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnterprisePermissionServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"MySQL", "PostgreSQL"})
    void installsDefaultRoleTemplatesIdempotently(String mode) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:ent_permissions_" + UUID.randomUUID()
                + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ks_ent_role_permissions ("
                        + "enterprise_id VARCHAR(128) NOT NULL,role VARCHAR(32) NOT NULL,"
                        + "permission VARCHAR(64) NOT NULL,PRIMARY KEY(enterprise_id,role,permission))");
            }

            EnterprisePermissionService service = new EnterprisePermissionService();
            service.ensureTemplates(connection, "ENT-1");
            service.ensureTemplates(connection, "ENT-1");

            assertEquals(12, count(connection,
                    "SELECT COUNT(*) FROM ks_ent_role_permissions WHERE enterprise_id='ENT-1'"));
            assertEquals(8, count(connection,
                    "SELECT COUNT(*) FROM ks_ent_role_permissions WHERE enterprise_id='ENT-1' AND role='CEO'"));
            assertEquals(4, count(connection,
                    "SELECT COUNT(*) FROM ks_ent_role_permissions WHERE enterprise_id='ENT-1' AND role='MANAGER'"));
        }
    }

    private static int count(java.sql.Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
