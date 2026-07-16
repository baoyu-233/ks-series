package org.kseco.extra.enterprise;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import org.kseco.KsEco;
import org.kseco.extra.EnterpriseAccessProvider;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Enterprise Extra-owned role templates and individual access grants. */
public final class EnterpriseAccessProviderImpl implements EnterpriseAccessProvider {
    private static final List<String> ROLES = List.of("CEO", "MANAGER", "EMPLOYEE");
    private static final Set<String> PERMISSIONS = Set.of(
            "MANAGE_MEMBERS", "MANAGE_PERMISSIONS", "MANAGE_BIDDING", "DECLARE_DIVIDEND",
            "VIEW_FINANCE", "MANAGE_FUNDS", "BLINDBOX_DRAW");

    private final KsEco eco;
    private final Gson gson = new Gson();

    public EnterpriseAccessProviderImpl(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        try (Connection conn = eco.ksCore().dataStore().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS ks_ent_role_permissions (enterprise_id TEXT NOT NULL, role TEXT NOT NULL, permission TEXT NOT NULL, PRIMARY KEY (enterprise_id, role, permission))");
        } catch (Exception e) {
            eco.getLogger().warning("Enterprise access schema initialization failed: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(String enterpriseId, UUID playerUuid, String permission) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            return conn != null && hasPermission(conn, enterpriseId, playerUuid, permission);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean handleAccessRequest(HttpExchange exchange, String path, String query) throws IOException {
        KsAuthManager.Session session = authenticate(exchange);
        if (session == null) {
            error(exchange, 401, "Authentication required");
            return true;
        }
        if (path.endsWith("/templates") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            getTemplates(exchange, session, query);
        } else if (path.endsWith("/templates/set") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            setTemplate(exchange, session);
        } else if (path.endsWith("/permissions") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            getIndividualPermissions(exchange, session, query);
        } else if (path.endsWith("/permissions/set") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            setIndividualPermission(exchange, session);
        } else if (path.endsWith("/member-role") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            setMemberRole(exchange, session);
        } else {
            return false;
        }
        return true;
    }

    private void getTemplates(HttpExchange exchange, KsAuthManager.Session session, String query) throws IOException {
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || enterpriseId == null || roleOf(conn, enterpriseId, session.playerUuid) == null) {
                error(exchange, 403, "Not an enterprise member");
                return;
            }
            ensureTemplates(conn, enterpriseId);
            Map<String, Set<String>> roles = new LinkedHashMap<>();
            for (String role : ROLES) roles.put(role, new LinkedHashSet<>());
            try (PreparedStatement ps = conn.prepareStatement("SELECT role, permission FROM ks_ent_role_permissions WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String role = rs.getString(1);
                    String permission = rs.getString(2);
                    if (roles.containsKey(role) && PERMISSIONS.contains(permission)) roles.get(role).add(permission);
                }
            }
            json(exchange, Map.of("roles", roles, "permissions", PERMISSIONS));
        } catch (Exception e) {
            error(exchange, 500, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void setTemplate(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = body == null ? null : (String) body.get("enterpriseId");
        String role = body == null ? null : normalizeRole(String.valueOf(body.get("role")));
        List<?> requested = body != null && body.get("permissions") instanceof List<?> list ? list : List.of();
        Set<String> permissions = new LinkedHashSet<>();
        for (Object value : requested) if (value != null) permissions.add(String.valueOf(value).toUpperCase(Locale.ROOT));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || enterpriseId == null || !ROLES.contains(role) || !PERMISSIONS.containsAll(permissions)
                    || !isOwner(conn, enterpriseId, session.playerUuid)) {
                error(exchange, 403, "Only the enterprise owner can edit role templates");
                return;
            }
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM ks_ent_role_permissions WHERE enterprise_id=? AND role=?")) {
                delete.setString(1, enterpriseId);
                delete.setString(2, role);
                delete.executeUpdate();
            }
            insertTemplatePermissions(conn, enterpriseId, role, permissions);
            audit(session, enterpriseId, "ENT_ROLE_TEMPLATE_SET", "role=" + role + " permissions=" + String.join(",", permissions));
            ok(exchange, "Role template saved");
        } catch (Exception e) {
            error(exchange, 500, e.getMessage());
        }
    }

    private void getIndividualPermissions(HttpExchange exchange, KsAuthManager.Session session, String query) throws IOException {
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || enterpriseId == null || roleOf(conn, enterpriseId, session.playerUuid) == null) {
                error(exchange, 403, "Not an enterprise member");
                return;
            }
            List<Map<String, Object>> permissions = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, permission, granted_by FROM ks_ent_permissions WHERE enterprise_id=? ORDER BY granted_at DESC")) {
                ps.setString(1, enterpriseId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    permissions.add(Map.of("player_uuid", rs.getString(1), "permission", rs.getString(2), "granted_by", rs.getString(3)));
                }
            }
            json(exchange, Map.of("permissions", permissions));
        } catch (Exception e) {
            error(exchange, 500, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void setIndividualPermission(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = body == null ? null : (String) body.get("enterpriseId");
        String target = body == null ? null : (String) body.get("playerUuid");
        String permission = body == null ? null : String.valueOf(body.get("permission")).toUpperCase(Locale.ROOT);
        boolean enabled = body == null || !Boolean.FALSE.equals(body.get("enabled"));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            UUID targetUuid = UUID.fromString(target);
            boolean actorOwner = isOwner(conn, enterpriseId, session.playerUuid);
            if (!PERMISSIONS.contains(permission) || roleOf(conn, enterpriseId, targetUuid) == null
                    || (!actorOwner && (!hasPermission(conn, enterpriseId, session.playerUuid, "MANAGE_PERMISSIONS")
                    || !hasPermission(conn, enterpriseId, session.playerUuid, permission)))) {
                error(exchange, 403, "Permission grant denied");
                return;
            }
            if (enabled) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO ks_ent_permissions (enterprise_id, player_uuid, permission, granted_by, granted_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, enterpriseId); ps.setString(2, targetUuid.toString()); ps.setString(3, permission);
                    ps.setString(4, session.playerUuid.toString()); ps.setLong(5, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, enterpriseId); ps.setString(2, targetUuid.toString()); ps.setString(3, permission); ps.executeUpdate();
                }
            }
            audit(session, enterpriseId, "ENT_PERMISSION_SET", (enabled ? "grant " : "revoke ") + target + " " + permission);
            ok(exchange, "Enterprise permission updated");
        } catch (Exception e) {
            error(exchange, 500, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void setMemberRole(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = body == null ? null : (String) body.get("enterpriseId");
        String target = body == null ? null : (String) body.get("playerUuid");
        String requestedRole = body == null ? null : normalizeRole(String.valueOf(body.get("role")));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            UUID targetUuid = UUID.fromString(target);
            String actorRole = roleOf(conn, enterpriseId, session.playerUuid);
            String targetRole = roleOf(conn, enterpriseId, targetUuid);
            boolean allowed = isOwner(conn, enterpriseId, session.playerUuid)
                    || (hasPermission(conn, enterpriseId, session.playerUuid, "MANAGE_MEMBERS")
                    && (("CEO".equals(actorRole) && !"CEO".equals(requestedRole))
                    || ("MANAGER".equals(actorRole) && "EMPLOYEE".equals(requestedRole))));
            if (!ROLES.contains(requestedRole) || "OWNER".equals(targetRole) || !allowed) {
                error(exchange, 403, "Role hierarchy does not allow this change");
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_members SET role=? WHERE enterprise_id=? AND player_uuid=?")) {
                ps.setString(1, requestedRole); ps.setString(2, enterpriseId); ps.setString(3, targetUuid.toString());
                if (ps.executeUpdate() == 0) { error(exchange, 404, "Target is not an enterprise member"); return; }
            }
            audit(session, enterpriseId, "ENT_MEMBER_ROLE_SET", target + " role=" + requestedRole);
            ok(exchange, "Member role updated");
        } catch (Exception e) {
            error(exchange, 500, e.getMessage());
        }
    }

    private void ensureTemplates(Connection conn, String enterpriseId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_ent_role_permissions WHERE enterprise_id=? LIMIT 1")) {
            ps.setString(1, enterpriseId);
            if (ps.executeQuery().next()) return;
        }
        insertTemplatePermissions(conn, enterpriseId, "CEO", PERMISSIONS);
        insertTemplatePermissions(conn, enterpriseId, "MANAGER", Set.of("MANAGE_MEMBERS", "MANAGE_BIDDING", "VIEW_FINANCE", "BLINDBOX_DRAW"));
    }

    private void insertTemplatePermissions(Connection conn, String enterpriseId, String role, Set<String> permissions) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO ks_ent_role_permissions (enterprise_id, role, permission) VALUES (?,?,?)")) {
            if (permissions.isEmpty()) {
                ps.setString(1, enterpriseId); ps.setString(2, role); ps.setString(3, "__NONE__"); ps.addBatch();
            }
            for (String permission : permissions) { ps.setString(1, enterpriseId); ps.setString(2, role); ps.setString(3, permission); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    private boolean hasPermission(Connection conn, String enterpriseId, UUID playerUuid, String permission) throws Exception {
        if (!PERMISSIONS.contains(permission)) return false;
        if (isOwner(conn, enterpriseId, playerUuid)) return true;
        ensureTemplates(conn, enterpriseId);
        String role = roleOf(conn, enterpriseId, playerUuid);
        if (role == null) return false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_ent_role_permissions WHERE enterprise_id=? AND role=? AND permission=? UNION ALL SELECT 1 FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=? AND permission=? LIMIT 1")) {
            ps.setString(1, enterpriseId); ps.setString(2, role); ps.setString(3, permission);
            ps.setString(4, enterpriseId); ps.setString(5, playerUuid.toString()); ps.setString(6, permission);
            return ps.executeQuery().next();
        }
    }

    private boolean isOwner(Connection conn, String enterpriseId, UUID playerUuid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
            ps.setString(1, enterpriseId); ResultSet rs = ps.executeQuery();
            return rs.next() && Arrays.stream(rs.getString(1).split(",")).anyMatch(value -> value.trim().equalsIgnoreCase(playerUuid.toString()));
        }
    }

    private String roleOf(Connection conn, String enterpriseId, UUID playerUuid) throws Exception {
        if (isOwner(conn, enterpriseId, playerUuid)) return "OWNER";
        try (PreparedStatement ps = conn.prepareStatement("SELECT role FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
            ps.setString(1, enterpriseId); ps.setString(2, playerUuid.toString()); ResultSet rs = ps.executeQuery();
            return rs.next() ? normalizeRole(rs.getString(1)) : null;
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return "EMPLOYEE";
        return switch (role.toUpperCase(Locale.ROOT)) { case "CEO", "CO_OWNER" -> "CEO"; case "MANAGER" -> "MANAGER"; case "OWNER" -> "OWNER"; default -> "EMPLOYEE"; };
    }

    private KsAuthManager.Session authenticate(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        if (token == null) token = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery()).get("token");
        return eco.ksCore().authManager().validate(token);
    }

    private void audit(KsAuthManager.Session session, String enterpriseId, String action, String detail) {
        try (Connection conn = eco.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO ks_audit_log (action,player_uuid,player_name,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, action); ps.setString(2, session.playerUuid.toString()); ps.setString(3, session.playerName);
            ps.setString(4, "enterprise"); ps.setString(5, enterpriseId); ps.setString(6, detail); ps.setLong(7, System.currentTimeMillis() / 1000); ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private void ok(HttpExchange exchange, String message) throws IOException { json(exchange, Map.of("message", message)); }
    private void error(HttpExchange exchange, int status, String message) throws IOException { KsPluginBridge.sendJson(exchange, status, gson.toJson(Map.of("error", message == null ? "Operation failed" : message))); }
    private void json(HttpExchange exchange, Object value) throws IOException { KsPluginBridge.sendJson(exchange, 200, gson.toJson(value)); }
}
