package org.kseco.currency;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal JDBC proxy for validating generated SQL without a remote database. */
final class JdbcContractRecorder {
    private final List<String> statements = new ArrayList<>();
    private final List<Boolean> autoCommitChanges = new ArrayList<>();
    private int commits;
    private int rollbacks;

    Connection openConnection() {
        Statement statement = (Statement) Proxy.newProxyInstance(
                JdbcContractRecorder.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeUpdate")) {
                        statements.add(normalizeSql((String) arguments[0]));
                        return 0;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                JdbcContractRecorder.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "setAutoCommit" -> {
                        autoCommitChanges.add((Boolean) arguments[0]);
                        yield null;
                    }
                    case "commit" -> {
                        commits++;
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks++;
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    List<Boolean> autoCommitChanges() {
        return List.copyOf(autoCommitChanges);
    }

    int commits() {
        return commits;
    }

    int rollbacks() {
        return rollbacks;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
