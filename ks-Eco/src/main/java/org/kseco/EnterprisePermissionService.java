package org.kseco;

import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves enterprise access from the owner list, per-enterprise role templates, and individual grants. */
public final class EnterprisePermissionService {
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_CEO = "CEO";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";

    public static final String MANAGE_MEMBERS = "MANAGE_MEMBERS";
    public static final String MANAGE_PERMISSIONS = "MANAGE_PERMISSIONS";
    public static final String MANAGE_BIDDING = "MANAGE_BIDDING";
    public static final String DECLARE_DIVIDEND = "DECLARE_DIVIDEND";
    public static final String VIEW_FINANCE = "VIEW_FINANCE";
    public static final String MANAGE_FUNDS = "MANAGE_FUNDS";
    public static final String MANAGE_PROPERTY = "MANAGE_PROPERTY";
    public static final String BLINDBOX_DRAW = "BLINDBOX_DRAW";

    private static final Set<String> PERMISSIONS = Set.of(
            MANAGE_MEMBERS, MANAGE_PERMISSIONS, MANAGE_BIDDING, DECLARE_DIVIDEND,
            VIEW_FINANCE, MANAGE_FUNDS, MANAGE_PROPERTY, BLINDBOX_DRAW);
    private static final List<String> EDITABLE_ROLES = List.of(ROLE_CEO, ROLE_MANAGER, ROLE_EMPLOYEE);

    public void ensureTemplates(Connection conn, String enterpriseId) throws SQLException {
        try (PreparedStatement exists = conn.prepareStatement(
                "SELECT 1 FROM ks_ent_role_permissions WHERE enterprise_id=? LIMIT 1")) {
            exists.setString(1, enterpriseId);
            try (ResultSet rs = exists.executeQuery()) {
                if (rs.next()) return;
            }
        }
        Map<String, Set<String>> defaults = new LinkedHashMap<>();
        defaults.put(ROLE_CEO, Set.of(MANAGE_MEMBERS, MANAGE_PERMISSIONS, MANAGE_BIDDING,
                DECLARE_DIVIDEND, VIEW_FINANCE, MANAGE_FUNDS, MANAGE_PROPERTY, BLINDBOX_DRAW));
        defaults.put(ROLE_MANAGER, Set.of(MANAGE_MEMBERS, MANAGE_BIDDING, VIEW_FINANCE, BLINDBOX_DRAW));
        defaults.put(ROLE_EMPLOYEE, Set.of());
        for (Map.Entry<String, Set<String>> entry : defaults.entrySet()) {
            for (String permission : entry.getValue()) {
                PortableSqlMutation.insertIfAbsent(conn,
                        "SELECT 1 FROM ks_ent_role_permissions "
                                + "WHERE enterprise_id=? AND role=? AND permission=?", exists -> {
                            exists.setString(1, enterpriseId);
                            exists.setString(2, entry.getKey());
                            exists.setString(3, permission);
                        }, "INSERT INTO ks_ent_role_permissions (enterprise_id,role,permission) VALUES (?,?,?)",
                        insert -> {
                            insert.setString(1, enterpriseId);
                            insert.setString(2, entry.getKey());
                            insert.setString(3, permission);
                        });
            }
        }
    }

    public Set<String> allowedPermissions() {
        return PERMISSIONS;
    }

    public List<String> editableRoles() {
        return EDITABLE_ROLES;
    }

    public String normalizedRole(String role) {
        if (role == null) return ROLE_EMPLOYEE;
        return switch (role.toUpperCase(java.util.Locale.ROOT)) {
            case "OWNER" -> ROLE_OWNER;
            case "CEO", "CO_OWNER" -> ROLE_CEO;
            case "MANAGER" -> ROLE_MANAGER;
            default -> ROLE_EMPLOYEE;
        };
    }

    public boolean isOwner(Connection conn, String enterpriseId, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
            ps.setString(1, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && ownerListContains(rs.getString(1), playerUuid);
            }
        }
    }

    public String roleOf(Connection conn, String enterpriseId, UUID playerUuid) throws SQLException {
        if (isOwner(conn, enterpriseId, playerUuid)) return ROLE_OWNER;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
            ps.setString(1, enterpriseId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? normalizedRole(rs.getString(1)) : null;
            }
        }
    }

    public boolean hasPermission(Connection conn, String enterpriseId, UUID playerUuid, String permission) throws SQLException {
        if (!PERMISSIONS.contains(permission)) return false;
        if (isOwner(conn, enterpriseId, playerUuid)) return true;
        ensureTemplates(conn, enterpriseId);
        String role = roleOf(conn, enterpriseId, playerUuid);
        if (role == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM ks_ent_role_permissions WHERE enterprise_id=? AND role=? AND permission=? " +
                        "UNION ALL SELECT 1 FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=? AND permission=? LIMIT 1")) {
            ps.setString(1, enterpriseId); ps.setString(2, role); ps.setString(3, permission);
            ps.setString(4, enterpriseId); ps.setString(5, playerUuid.toString()); ps.setString(6, permission);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public boolean canAssignRole(Connection conn, String enterpriseId, UUID actor, String requestedRole) throws SQLException {
        String role = normalizedRole(requestedRole);
        if (!EDITABLE_ROLES.contains(role)) return false;
        if (isOwner(conn, enterpriseId, actor)) return true;
        String actorRole = roleOf(conn, enterpriseId, actor);
        if (ROLE_CEO.equals(actorRole)) return !ROLE_CEO.equals(role);
        return ROLE_MANAGER.equals(actorRole) && ROLE_EMPLOYEE.equals(role);
    }

    public static boolean ownerListContains(String owners, UUID playerUuid) {
        if (owners == null || playerUuid == null) return false;
        for (String value : owners.split(",")) {
            if (value.trim().equalsIgnoreCase(playerUuid.toString())) return true;
        }
        return false;
    }
}
