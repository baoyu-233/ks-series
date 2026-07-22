package org.kseco.extra.bank;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import org.kseco.KsEco;
import org.kseco.extra.BankAccessProvider;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bank Extra-owned role templates and individual access grants. */
public final class BankAccessProviderImpl implements BankAccessProvider {
    private static final List<String> ROLES = List.of("DIRECTOR", "MANAGER", "TELLER");
    private static final Set<String> PERMISSIONS = Set.of(
            "MANAGE_MEMBERS", "MANAGE_PERMISSIONS", "VIEW_FINANCE", "SET_RATES",
            "ISSUE_LOAN", "APPROVE_LOAN", "DECLARE_DIVIDEND");

    private final KsEco eco;
    private final BankManager bankManager;
    private final Gson gson = new Gson();

    public BankAccessProviderImpl(KsEco eco, BankManager bankManager) {
        this.eco = eco;
        this.bankManager = bankManager;
    }

    public void init() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new java.sql.SQLException("database unavailable");
            BankSchema.ensureAccessTables(conn);
        } catch (Exception e) {
            eco.getLogger().warning("Bank access schema initialization failed: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(String bankId, UUID playerUuid, String permission) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            return conn != null && hasPermission(conn, bankId, playerUuid, permission);
        } catch (Exception ignored) { return false; }
    }

    @Override
    public List<SettlementReview> listLoanRepaymentReviews() throws java.sql.SQLException {
        return bankManager.listLoanRepaymentReviews();
    }

    @Override
    public SettlementResolution resolveLoanRepaymentReview(String id, String action)
            throws java.sql.SQLException {
        return bankManager.resolveLoanRepaymentReview(id, action);
    }

    @Override
    public boolean handleAccessRequest(HttpExchange exchange, String path, String query) throws IOException {
        KsAuthManager.Session session = authenticate(exchange);
        if (session == null) { error(exchange, 401, "Authentication required"); return true; }
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
        } else return false;
        return true;
    }

    private void getTemplates(HttpExchange exchange, KsAuthManager.Session session, String query) throws IOException {
        String bankId = KsPluginBridge.parseQuery(query).get("bankId");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || bankId == null || (!session.isAdmin && roleOf(conn, bankId, session.playerUuid) == null)) {
                error(exchange, 403, "Not a bank member"); return;
            }
            ensureTemplates(conn, bankId);
            Map<String, Set<String>> roles = new LinkedHashMap<>();
            for (String role : ROLES) roles.put(role, new LinkedHashSet<>());
            try (PreparedStatement ps = conn.prepareStatement("SELECT role, permission FROM ks_bank_role_permissions WHERE bank_id=?")) {
                ps.setString(1, bankId); ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String role = rs.getString(1), permission = rs.getString(2);
                    if (roles.containsKey(role) && PERMISSIONS.contains(permission)) roles.get(role).add(permission);
                }
            }
            json(exchange, Map.of("roles", roles, "permissions", PERMISSIONS));
        } catch (Exception e) { error(exchange, 500, e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void setTemplate(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = body == null ? null : (String) body.get("bankId");
        String role = body == null ? null : normalizeRole(String.valueOf(body.get("role")));
        List<?> requested = body != null && body.get("permissions") instanceof List<?> list ? list : List.of();
        Set<String> permissions = new LinkedHashSet<>();
        for (Object value : requested) if (value != null) permissions.add(String.valueOf(value).toUpperCase(Locale.ROOT));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || bankId == null || !ROLES.contains(role) || !PERMISSIONS.containsAll(permissions)
                    || (!session.isAdmin && !isOwner(conn, bankId, session.playerUuid))) {
                error(exchange, 403, "Only the bank owner can edit role templates"); return;
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_bank_role_permissions WHERE bank_id=? AND role=?")) {
                ps.setString(1, bankId); ps.setString(2, role); ps.executeUpdate();
            }
            insertTemplatePermissions(conn, bankId, role, permissions);
            audit(session, bankId, "BANK_ROLE_TEMPLATE_SET", "role=" + role + " permissions=" + String.join(",", permissions));
            ok(exchange, "Bank role template saved");
        } catch (Exception e) { error(exchange, 500, e.getMessage()); }
    }

    private void getIndividualPermissions(HttpExchange exchange, KsAuthManager.Session session, String query) throws IOException {
        String bankId = KsPluginBridge.parseQuery(query).get("bankId");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || bankId == null || (!session.isAdmin && roleOf(conn, bankId, session.playerUuid) == null)) {
                error(exchange, 403, "Not a bank member"); return;
            }
            List<Map<String, Object>> permissions = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, permission, granted_by FROM ks_bank_permissions WHERE bank_id=? ORDER BY granted_at DESC")) {
                ps.setString(1, bankId); ResultSet rs = ps.executeQuery();
                while (rs.next()) permissions.add(Map.of("player_uuid", rs.getString(1), "permission", rs.getString(2), "granted_by", rs.getString(3)));
            }
            json(exchange, Map.of("permissions", permissions));
        } catch (Exception e) { error(exchange, 500, e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void setIndividualPermission(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = body == null ? null : (String) body.get("bankId");
        String target = body == null ? null : (String) body.get("playerUuid");
        String permission = body == null ? null : String.valueOf(body.get("permission")).toUpperCase(Locale.ROOT);
        boolean enabled = body == null || !Boolean.FALSE.equals(body.get("enabled"));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            UUID targetUuid = UUID.fromString(target);
            boolean owner = session.isAdmin || isOwner(conn, bankId, session.playerUuid);
            if (!PERMISSIONS.contains(permission) || roleOf(conn, bankId, targetUuid) == null
                    || (!owner && (!hasPermission(conn, bankId, session.playerUuid, "MANAGE_PERMISSIONS")
                    || !hasPermission(conn, bankId, session.playerUuid, permission)))) {
                error(exchange, 403, "Permission grant denied"); return;
            }
            if (enabled) {
                long now = System.currentTimeMillis() / 1000;
                BankSqlMutation.upsert(conn,
                        "UPDATE ks_bank_permissions SET granted_by=?,granted_at=? "
                                + "WHERE bank_id=? AND player_uuid=? AND permission=?", update -> {
                            update.setString(1, session.playerUuid.toString());
                            update.setLong(2, now);
                            update.setString(3, bankId);
                            update.setString(4, targetUuid.toString());
                            update.setString(5, permission);
                        }, "INSERT INTO ks_bank_permissions "
                                + "(bank_id,player_uuid,permission,granted_by,granted_at) VALUES (?,?,?,?,?)", insert -> {
                            insert.setString(1, bankId);
                            insert.setString(2, targetUuid.toString());
                            insert.setString(3, permission);
                            insert.setString(4, session.playerUuid.toString());
                            insert.setLong(5, now);
                        });
            } else {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, bankId); ps.setString(2, targetUuid.toString()); ps.setString(3, permission); ps.executeUpdate();
                }
            }
            audit(session, bankId, "BANK_PERMISSION_SET", (enabled ? "grant " : "revoke ") + target + " " + permission);
            ok(exchange, "Bank permission updated");
        } catch (Exception e) { error(exchange, 500, e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void setMemberRole(HttpExchange exchange, KsAuthManager.Session session) throws IOException {
        Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = body == null ? null : (String) body.get("bankId");
        String target = body == null ? null : (String) body.get("playerUuid");
        String role = body == null ? null : normalizeRole(String.valueOf(body.get("role")));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            UUID targetUuid = UUID.fromString(target);
            if (!ROLES.contains(role) || isOwner(conn, bankId, targetUuid)
                    || (!session.isAdmin && !isOwner(conn, bankId, session.playerUuid))) {
                error(exchange, 403, "Only the bank owner can change member roles"); return;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_bank_members SET role=? WHERE bank_id=? AND player_uuid=?")) {
                ps.setString(1, role); ps.setString(2, bankId); ps.setString(3, targetUuid.toString());
                if (ps.executeUpdate() == 0) { error(exchange, 404, "Target is not a bank member"); return; }
            }
            audit(session, bankId, "BANK_MEMBER_ROLE_SET", target + " role=" + role);
            ok(exchange, "Bank member role updated");
        } catch (Exception e) { error(exchange, 500, e.getMessage()); }
    }

    private void ensureTemplates(Connection conn, String bankId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_role_permissions WHERE bank_id=? LIMIT 1")) {
            ps.setString(1, bankId); if (ps.executeQuery().next()) return;
        }
        insertTemplatePermissions(conn, bankId, "DIRECTOR", PERMISSIONS);
        insertTemplatePermissions(conn, bankId, "MANAGER", Set.of("MANAGE_MEMBERS", "VIEW_FINANCE", "ISSUE_LOAN", "APPROVE_LOAN"));
        insertTemplatePermissions(conn, bankId, "TELLER", Set.of("VIEW_FINANCE"));
    }

    private void insertTemplatePermissions(Connection conn, String bankId, String role, Set<String> permissions) throws Exception {
        for (String permission : permissions) {
            BankSqlMutation.insertIfAbsent(conn,
                    "SELECT 1 FROM ks_bank_role_permissions WHERE bank_id=? AND role=? AND permission=?", exists -> {
                        exists.setString(1, bankId);
                        exists.setString(2, role);
                        exists.setString(3, permission);
                    }, "INSERT INTO ks_bank_role_permissions (bank_id,role,permission) VALUES (?,?,?)", insert -> {
                        insert.setString(1, bankId);
                        insert.setString(2, role);
                        insert.setString(3, permission);
                    });
        }
    }

    private boolean hasPermission(Connection conn, String bankId, UUID playerUuid, String permission) throws Exception {
        if (!PERMISSIONS.contains(permission)) return false;
        if (isOwner(conn, bankId, playerUuid)) return true;
        ensureTemplates(conn, bankId);
        String role = roleOf(conn, bankId, playerUuid);
        if (role == null) return false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_role_permissions WHERE bank_id=? AND role=? AND permission=? UNION ALL SELECT 1 FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=? AND permission=? LIMIT 1")) {
            ps.setString(1, bankId); ps.setString(2, role); ps.setString(3, permission);
            ps.setString(4, bankId); ps.setString(5, playerUuid.toString()); ps.setString(6, permission);
            return ps.executeQuery().next();
        }
    }

    private boolean isOwner(Connection conn, String bankId, UUID playerUuid) throws Exception {
        if (bankId == null || playerUuid == null) return false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId); ResultSet rs = ps.executeQuery();
            return rs.next() && Arrays.stream(rs.getString(1).split(",")).anyMatch(value -> value.trim().equalsIgnoreCase(playerUuid.toString()));
        }
    }

    private String roleOf(Connection conn, String bankId, UUID playerUuid) throws Exception {
        if (isOwner(conn, bankId, playerUuid)) return "OWNER";
        try (PreparedStatement ps = conn.prepareStatement("SELECT role FROM ks_bank_members WHERE bank_id=? AND player_uuid=?")) {
            ps.setString(1, bankId); ps.setString(2, playerUuid.toString()); ResultSet rs = ps.executeQuery();
            return rs.next() ? normalizeRole(rs.getString(1)) : null;
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return "TELLER";
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "OWNER" -> "OWNER";
            case "DIRECTOR", "CEO", "CO_OWNER" -> "DIRECTOR";
            case "MANAGER" -> "MANAGER";
            default -> "TELLER";
        };
    }

    private KsAuthManager.Session authenticate(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        if (token == null) token = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery()).get("token");
        return eco.ksCore().authManager().validate(token);
    }

    private void audit(KsAuthManager.Session session, String bankId, String action, String detail) {
        try (Connection conn = eco.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO ks_audit_log (action,player_uuid,player_name,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, action); ps.setString(2, session.playerUuid.toString()); ps.setString(3, session.playerName);
            ps.setString(4, "bank"); ps.setString(5, bankId); ps.setString(6, detail); ps.setLong(7, System.currentTimeMillis() / 1000); ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private void ok(HttpExchange exchange, String message) throws IOException { json(exchange, Map.of("message", message)); }
    private void error(HttpExchange exchange, int status, String message) throws IOException { KsPluginBridge.sendJson(exchange, status, gson.toJson(Map.of("error", message == null ? "Operation failed" : message))); }
    private void json(HttpExchange exchange, Object value) throws IOException { KsPluginBridge.sendJson(exchange, 200, gson.toJson(value)); }
}
