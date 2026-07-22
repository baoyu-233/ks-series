package org.kseries.rpg;

import org.kseries.rpg.api.RpgSeasonStatusApi;
import org.kseries.rpg.season.JdbcSeasonStore;
import org.kseries.rpg.season.SeasonService;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Lifecycle bridge. Runtime status is cached so server-thread readers never touch JDBC. */
final class SeasonRuntime implements RpgSeasonStatusApi, AutoCloseable {
    private final Logger logger;
    private final ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();

    private volatile SeasonService service = new SeasonService();
    private volatile RuntimeStatus status = disabledStatus("");
    private volatile boolean closed;

    SeasonRuntime(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ks-rpg-season-db");
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(factory);
    }

    void reload(SeasonRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        if (closed) return;
        long requestedGeneration = generation.incrementAndGet();
        service = new SeasonService();
        if (!config.enabled()) {
            status = disabledStatus(config.databasePath().toString());
            return;
        }

        status = new RuntimeStatus(true, false, RuntimeState.STARTING,
                "赛季存储正在初始化；未启动任何赛季事件。", config.databasePath().toString());
        worker.execute(() -> initialize(requestedGeneration, config));
    }

    SeasonService service() {
        return service;
    }

    @Override
    public RuntimeStatus status() {
        return status;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        service = new SeasonService();
        status = new RuntimeStatus(false, false, RuntimeState.STOPPED,
                "赛季运行时已停止。", status.storagePath());
        worker.shutdownNow();
    }

    private void initialize(long requestedGeneration, SeasonRuntimeConfig config) {
        try {
            Files.createDirectories(config.databasePath().getParent());
            Class.forName("org.sqlite.JDBC");
            JdbcSeasonStore store = new JdbcSeasonStore(new SqliteConnectionFactory(config));
            SeasonService candidate = SeasonService.enabled(store, config.rules(), Clock.systemUTC());
            candidate.initializeStore();
            if (!canPromote(requestedGeneration)) return;
            service = candidate;
            status = new RuntimeStatus(true, true, RuntimeState.READY,
                    "赛季存储已就绪；当前未创建赛季、未启动事件、未改动玩家数据。",
                    config.databasePath().toString());
            logger.info("Season storage ready; no season events or player mutations were started.");
        } catch (Exception failure) {
            if (!canPromote(requestedGeneration)) return;
            service = new SeasonService();
            status = new RuntimeStatus(true, false, RuntimeState.FAILED,
                    conciseFailure(failure), config.databasePath().toString());
            logger.log(Level.SEVERE, "Season storage initialization failed; season service remains disabled.", failure);
        }
    }

    private boolean canPromote(long requestedGeneration) {
        return !closed && generation.get() == requestedGeneration && !Thread.currentThread().isInterrupted();
    }

    private static RuntimeStatus disabledStatus(String storagePath) {
        return new RuntimeStatus(false, false, RuntimeState.DISABLED,
                "赛季系统未启用（season.enabled=false）。", storagePath);
    }

    private static String conciseFailure(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return failure.getClass().getSimpleName() + ": " + message;
    }

    private static final class SqliteConnectionFactory implements JdbcSeasonStore.ConnectionFactory {
        private final SeasonRuntimeConfig config;
        private boolean journalConfigured;

        private SqliteConnectionFactory(SeasonRuntimeConfig config) {
            this.config = config;
        }

        @Override
        public synchronized Connection open() throws SQLException {
            if (Thread.currentThread().isInterrupted()) {
                throw new SQLException("season worker interrupted", "57014");
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + config.databasePath());
            boolean ready = false;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + config.busyTimeoutMillis());
                if (!journalConfigured) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA synchronous=NORMAL");
                    journalConfigured = true;
                }
                ready = true;
                return connection;
            } finally {
                if (!ready) connection.close();
            }
        }
    }
}
