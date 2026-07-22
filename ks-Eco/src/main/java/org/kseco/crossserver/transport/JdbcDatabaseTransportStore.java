package org.kseco.crossserver.transport;

import org.kseco.crossserver.sql.SqlDialect;
import org.kseco.crossserver.sql.SqlIdentifiers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Direct JDBC store for {@link DatabasePollingTransport}. */
public final class JdbcDatabaseTransportStore implements DatabaseTransportStore {
    private static final List<String> EVENT_COLUMNS = List.of(
            "event_id", "topic", "source_server_id", "target_server_id", "occurred_at_ms",
            "available_at_ms", "expires_at_ms", "content_type", "payload", "headers_payload"
    );
    private static final List<String> CURSOR_COLUMNS = List.of(
            "consumer_id", "publish_sequence", "available_at_ms", "occurred_at_ms", "event_id"
    );
    private static final List<String> SEQUENCE_COLUMNS = List.of("sequence_id", "next_value");
    private static final int MAX_HEADERS = 256;
    private static final int MAX_HEADER_BYTES = 65_536;

    private final ConnectionFactory connections;
    private final SqlDialect dialect;
    private final Tables tables;
    private final String events;
    private final String cursors;
    private final String sequence;
    private volatile boolean initialized;

    public JdbcDatabaseTransportStore(ConnectionFactory connections, SqlDialect dialect) {
        this(connections, dialect, Tables.defaults());
    }

    public JdbcDatabaseTransportStore(ConnectionFactory connections, SqlDialect dialect, Tables tables) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.events = SqlIdentifiers.quoteQualified(dialect, tables.events());
        this.cursors = SqlIdentifiers.quoteQualified(dialect, tables.cursors());
        this.sequence = SqlIdentifiers.quoteQualified(dialect, tables.sequence());
    }

    public void initialize() throws SQLException {
        try (Connection connection = openConnection()) {
            initialize(connection);
        }
    }

    @Override
    public TransportPublishReceipt publish(TransportEvent event) throws Exception {
        TransportEvent checked = Objects.requireNonNull(event, "event");
        requireIdentifierLength(checked.eventId(), "eventId");
        try (Connection connection = openConnection()) {
            ensureInitialized(connection);
            return transactional(connection, () -> {
                TransportEvent existing = findEvent(connection, checked.eventId());
                if (existing != null) {
                    requireSameEvent(existing, checked);
                    return new TransportPublishReceipt(
                            checked.eventId(),
                            TransportPublishReceipt.Disposition.ALREADY_PRESENT,
                            databaseTimeMillis(connection)
                    );
                }

                long publishSequence = nextSequence(connection);
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(insertEventIfAbsentSql())) {
                    statement.setLong(1, publishSequence);
                    bindEvent(statement, checked, 2);
                    inserted = statement.executeUpdate();
                }
                if (inserted == 0) {
                    existing = findEvent(connection, checked.eventId());
                    if (existing == null) throw new SQLException("transport event insert lost without a stored row");
                    requireSameEvent(existing, checked);
                }
                return new TransportPublishReceipt(
                        checked.eventId(),
                        inserted > 0 ? TransportPublishReceipt.Disposition.PUBLISHED
                                : TransportPublishReceipt.Disposition.ALREADY_PRESENT,
                        databaseTimeMillis(connection)
                );
            });
        }
    }

    @Override
    public PollCursor loadCursor(String consumerId) throws Exception {
        String consumer = requireConsumerId(consumerId);
        try (Connection connection = openConnection()) {
            ensureInitialized(connection);
            try (PreparedStatement statement = connection.prepareStatement(selectCursorSql())) {
                statement.setString(1, consumer);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return PollCursor.initial();
                    return new PollCursor(result.getLong(1));
                }
            }
        }
    }

    @Override
    public PollBatch poll(PollRequest request) throws Exception {
        PollRequest checked = Objects.requireNonNull(request, "request");
        try (Connection connection = openConnection()) {
            ensureInitialized(connection);
            int scanLimit = checked.limit() == Integer.MAX_VALUE ? 4096 : Math.min(4096, Math.max(checked.limit() + 1, checked.limit() * 8));
            List<SequencedEvent> fetched = new ArrayList<>(Math.min(scanLimit, 1024));
            try (PreparedStatement statement = connection.prepareStatement(pollSql())) {
                statement.setLong(1, checked.after().publishSequence());
                statement.setInt(2, scanLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) fetched.add(new SequencedEvent(result.getLong(1), readEvent(result, 2)));
                }
            }
            List<TransportEvent> events = new ArrayList<>(Math.min(checked.limit(), fetched.size()));
            long nextSequence = checked.after().publishSequence();
            boolean blockedByFuture = false;
            boolean stoppedAtBatchLimit = false;
            for (SequencedEvent fetchedEvent : fetched) {
                TransportEvent event = fetchedEvent.event();
                boolean applicable = event.isBroadcast() || checked.localServerId().equals(event.targetServerId());
                if (applicable && event.availableAtEpochMillis() > checked.nowEpochMillis()) {
                    blockedByFuture = true;
                    break;
                }
                nextSequence = fetchedEvent.sequence();
                if (!applicable || event.isExpired(checked.nowEpochMillis())) continue;
                events.add(event);
                if (events.size() >= checked.limit()) {
                    stoppedAtBatchLimit = true;
                    break;
                }
            }
            boolean hasMore = !blockedByFuture && (stoppedAtBatchLimit || fetched.size() == scanLimit);
            return new PollBatch(checked.after(), events, new PollCursor(nextSequence), hasMore,
                    databaseTimeMillis(connection));
        }
    }

    @Override
    public boolean advanceCursor(String consumerId, PollCursor expected, PollCursor next) throws Exception {
        String consumer = requireConsumerId(consumerId);
        PollCursor checkedExpected = Objects.requireNonNull(expected, "expected");
        PollCursor checkedNext = Objects.requireNonNull(next, "next");
        if (checkedNext.compareTo(checkedExpected) <= 0) {
            throw new IllegalArgumentException("next cursor must be strictly after expected");
        }
        try (Connection connection = openConnection()) {
            ensureInitialized(connection);
            return transactional(connection, () -> {
                try (PreparedStatement statement = connection.prepareStatement(updateCursorSql())) {
                    statement.setLong(1, checkedNext.publishSequence());
                    statement.setString(2, consumer);
                    statement.setLong(3, checkedExpected.publishSequence());
                    if (statement.executeUpdate() == 1) return true;
                }

                if (!checkedExpected.equals(PollCursor.initial())) return false;
                try (PreparedStatement statement = connection.prepareStatement(insertCursorIfAbsentSql())) {
                    statement.setString(1, consumer);
                    statement.setLong(2, checkedNext.publishSequence());
                    statement.setLong(3, 0L);
                    statement.setLong(4, 0L);
                    statement.setString(5, "");
                    return statement.executeUpdate() > 0;
                }
            });
        }
    }

    private synchronized void initialize(Connection connection) throws SQLException {
        if (initialized) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute(createEventsTableSql());
            statement.execute(createCursorsTableSql());
            statement.execute(createSequenceTableSql());
        }
        ensurePublishSequenceColumns(connection);
        initializeSequence(connection);
        backfillPublishSequences(connection);
        initialized = true;
    }

    private void ensureInitialized(Connection connection) throws SQLException {
        if (!initialized) initialize(connection);
    }

    private Connection openConnection() throws SQLException {
        Connection connection = connections.open();
        if (connection == null) throw new SQLException("connection factory returned null");
        return connection;
    }

    private long databaseTimeMillis(Connection connection) throws SQLException {
        String query = "SELECT " + dialect.currentEpochMillisExpression();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            if (!result.next()) throw new SQLException("database time query returned no row");
            return result.getLong(1);
        }
    }

    private TransportEvent findEvent(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(selectEventSql())) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readEvent(result) : null;
            }
        }
    }

    private void bindEvent(PreparedStatement statement, TransportEvent event, int offset) throws SQLException {
        statement.setString(offset, event.eventId());
        statement.setString(offset + 1, event.topic());
        statement.setString(offset + 2, event.sourceServerId());
        statement.setString(offset + 3, event.targetServerId());
        statement.setLong(offset + 4, event.occurredAtEpochMillis());
        statement.setLong(offset + 5, event.availableAtEpochMillis());
        statement.setLong(offset + 6, event.expiresAtEpochMillis());
        statement.setString(offset + 7, event.contentType());
        statement.setBytes(offset + 8, event.payload());
        statement.setBytes(offset + 9, encodeHeaders(event.headers()));
    }

    private TransportEvent readEvent(ResultSet result) throws SQLException {
        return readEvent(result, 1);
    }

    private TransportEvent readEvent(ResultSet result, int offset) throws SQLException {
        return new TransportEvent(
                result.getString(offset), result.getString(offset + 1), result.getString(offset + 2),
                result.getString(offset + 3), result.getLong(offset + 4), result.getLong(offset + 5),
                result.getLong(offset + 6), result.getString(offset + 7), result.getBytes(offset + 8),
                decodeHeaders(result.getBytes(offset + 9))
        );
    }

    private String createEventsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + events + " ("
                + q("publish_sequence") + " BIGINT NOT NULL UNIQUE, "
                + q("event_id") + " " + identifierType(191) + " PRIMARY KEY, "
                + q("topic") + " " + identifierType(191) + " NOT NULL, "
                + q("source_server_id") + " " + identifierType(191) + " NOT NULL, "
                + q("target_server_id") + " " + identifierType(191) + " NOT NULL, "
                + q("occurred_at_ms") + " BIGINT NOT NULL, "
                + q("available_at_ms") + " BIGINT NOT NULL, "
                + q("expires_at_ms") + " BIGINT NOT NULL, "
                + q("content_type") + " " + identifierType(191) + " NOT NULL, "
                + q("payload") + " " + binaryType() + " NOT NULL, "
                + q("headers_payload") + " " + binaryType() + " NOT NULL)";
    }

    private String createCursorsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + cursors + " ("
                + q("consumer_id") + " " + identifierType(191) + " PRIMARY KEY, "
                + q("publish_sequence") + " BIGINT NOT NULL, "
                + q("available_at_ms") + " BIGINT NOT NULL DEFAULT 0, "
                + q("occurred_at_ms") + " BIGINT NOT NULL DEFAULT 0, "
                + q("event_id") + " " + identifierType(191) + " NOT NULL DEFAULT '')";
    }

    private String createSequenceTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + sequence + " (" + q("sequence_id")
                + " INTEGER PRIMARY KEY, " + q("next_value") + " BIGINT NOT NULL)";
    }

    private String insertEventIfAbsentSql() {
        List<String> columns = new ArrayList<>(EVENT_COLUMNS.size() + 1);
        columns.add("publish_sequence");
        columns.addAll(EVENT_COLUMNS);
        return dialect.insertIgnore(tables.events(), columns, List.of("event_id"));
    }

    private String insertCursorIfAbsentSql() {
        return dialect.insertIgnore(tables.cursors(), CURSOR_COLUMNS, List.of("consumer_id"));
    }

    private String selectEventSql() {
        return "SELECT " + joined(EVENT_COLUMNS) + " FROM " + events + " WHERE " + q("event_id") + " = ?";
    }

    private String selectCursorSql() {
        return "SELECT " + q("publish_sequence")
                + " FROM " + cursors + " WHERE " + q("consumer_id") + " = ?";
    }

    private String updateCursorSql() {
        return "UPDATE " + cursors + " SET " + q("publish_sequence") + " = ? WHERE "
                + q("consumer_id") + " = ? AND " + q("publish_sequence") + " = ?";
    }

    private String pollSql() {
        return "SELECT " + q("publish_sequence") + ", " + joined(EVENT_COLUMNS) + " FROM " + events
                + " WHERE " + q("publish_sequence") + " > ? ORDER BY " + q("publish_sequence") + " LIMIT ?";
    }

    private void ensurePublishSequenceColumns(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try { statement.execute("ALTER TABLE " + events + " ADD COLUMN " + q("publish_sequence") + " BIGINT"); }
            catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE " + cursors + " ADD COLUMN " + q("publish_sequence") + " BIGINT DEFAULT 0"); }
            catch (SQLException ignored) { }
            statement.executeUpdate("UPDATE " + cursors + " SET " + q("publish_sequence") + " = 0 WHERE "
                    + q("publish_sequence") + " IS NULL");
        }
    }

    private void initializeSequence(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                dialect.insertIgnore(tables.sequence(), SEQUENCE_COLUMNS, List.of("sequence_id")))) {
            statement.setInt(1, 1);
            statement.setLong(2, 0L);
            statement.executeUpdate();
        }
    }

    private long nextSequence(Connection connection) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + sequence + " SET " + q("next_value") + " = " + q("next_value") + " + 1 WHERE "
                        + q("sequence_id") + " = 1")) {
            if (update.executeUpdate() != 1) throw new SQLException("transport sequence row is missing");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + q("next_value") + " FROM " + sequence
                     + " WHERE " + q("sequence_id") + " = 1")) {
            if (!result.next()) throw new SQLException("transport sequence row disappeared");
            return result.getLong(1);
        }
    }

    private void backfillPublishSequences(Connection connection) throws SQLException {
        while (true) {
            String eventId;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT " + q("event_id") + " FROM " + events
                         + " WHERE " + q("publish_sequence") + " IS NULL ORDER BY " + q("available_at_ms")
                         + ", " + q("occurred_at_ms") + ", " + q("event_id") + " LIMIT 1")) {
                if (!result.next()) return;
                eventId = result.getString(1);
            }
            long allocated = nextSequence(connection);
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + events + " SET "
                    + q("publish_sequence") + " = ? WHERE " + q("event_id") + " = ? AND "
                    + q("publish_sequence") + " IS NULL")) {
                update.setLong(1, allocated);
                update.setString(2, eventId);
                update.executeUpdate();
            }
        }
    }

    private String joined(List<String> columns) {
        return columns.stream().map(this::q).collect(java.util.stream.Collectors.joining(", "));
    }

    private String q(String identifier) {
        String validated = SqlIdentifiers.requireSimple(identifier);
        char quote = dialect == SqlDialect.MYSQL ? '`' : '"';
        return quote + validated + quote;
    }

    private String identifierType(int length) {
        return dialect == SqlDialect.MYSQL
                ? "VARCHAR(" + length + ") CHARACTER SET ascii COLLATE ascii_bin"
                : "TEXT";
    }

    private String binaryType() {
        return switch (dialect) {
            case SQLITE -> "BLOB";
            case MYSQL -> "LONGBLOB";
            case POSTGRESQL -> "BYTEA";
        };
    }

    private static byte[] encodeHeaders(Map<String, String> headers) {
        if (headers.size() > MAX_HEADERS) throw new IllegalArgumentException("too many transport headers");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(headers.size());
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    writeString(output, entry.getKey());
                    writeString(output, entry.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode transport headers", exception);
        }
    }

    private static Map<String, String> decodeHeaders(byte[] payload) throws SQLException {
        if (payload == null) throw new SQLException("transport header payload is null");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_HEADERS) throw new IOException("invalid transport header count");
            Map<String, String> headers = new LinkedHashMap<>(count);
            for (int index = 0; index < count; index++) {
                String key = readString(input);
                String value = readString(input);
                if (headers.put(key, value) != null) throw new IOException("duplicate transport header");
            }
            if (input.read() != -1) throw new IOException("trailing transport header data");
            return Map.copyOf(headers);
        } catch (IOException exception) {
            throw new SQLException("invalid transport header payload", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = Objects.requireNonNull(value, "header value").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_HEADER_BYTES) throw new IllegalArgumentException("transport header is too large");
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException exception) {
            throw new IOException("truncated transport header", exception);
        }
        if (length < 0 || length > MAX_HEADER_BYTES) throw new IOException("invalid transport header length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated transport header");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireSameEvent(TransportEvent existing, TransportEvent requested) {
        if (!existing.equals(requested)) {
            throw new IllegalStateException("transport event ID collision with different content: " + requested.eventId());
        }
    }

    private static String requireConsumerId(String consumerId) {
        String checked = Objects.requireNonNull(consumerId, "consumerId").trim();
        if (checked.isEmpty() || checked.length() > 191) {
            throw new IllegalArgumentException("consumerId must be non-blank and at most 191 characters");
        }
        return checked;
    }

    private static void requireIdentifierLength(String value, String name) {
        if (value.length() > 191) throw new IllegalArgumentException(name + " must be at most 191 characters");
    }

    private static <T> T transactional(Connection connection, SqlWork<T> work) throws SQLException {
        if (!connection.getAutoCommit()) return work.run();
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    public record Tables(String events, String cursors, String sequence) {
        public Tables(String events, String cursors) {
            this(events, cursors, events + "_sequence");
        }

        public Tables {
            events = SqlIdentifiers.requireQualified(events);
            cursors = SqlIdentifiers.requireQualified(cursors);
            sequence = SqlIdentifiers.requireQualified(sequence);
        }

        public static Tables defaults() {
            return new Tables("ks_crossserver_transport_events", "ks_crossserver_transport_cursors");
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private record SequencedEvent(long sequence, TransportEvent event) { }
}
