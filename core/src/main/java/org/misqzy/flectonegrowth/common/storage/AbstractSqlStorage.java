package org.misqzy.flectonegrowth.common.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.storage.migration.LegacyTableMigration;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaVersionStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Shared JDBC/HikariCP plumbing for the MySQL and MariaDB backends. Unlike
 * the original version this has no dependency on {@code org.bukkit.plugin.Plugin} -
 * it only needs a {@link Logger}, which every platform module that depends
 * on core (currently just the Bukkit module) can hand it.
 */
public abstract class AbstractSqlStorage implements Storage {

    protected final Logger logger;
    protected HikariDataSource dataSource;

    private final SqlConnectionSettings settings;
    private final String pluginVersion;
    private final TableNames tables;
    private final String upsertSchemaVersionSql;

    protected AbstractSqlStorage(Logger logger, SqlConnectionSettings settings, String pluginVersion) {
        this.logger = logger;
        this.settings = settings;
        this.pluginVersion = pluginVersion;
        this.tables = new TableNames(settings.tablePrefix());
        this.upsertSchemaVersionSql = "INSERT INTO " + tables.schemaVersion() + " (id, version) VALUES (1, ?) "
                + "ON DUPLICATE KEY UPDATE version = VALUES(version)";
    }

    protected abstract String buildJdbcUrl(SqlConnectionSettings settings);
    protected abstract String driverClassName();
    protected abstract String storageName();

    @Override
    public boolean initialize() {
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(buildJdbcUrl(settings));
            cfg.setUsername(settings.username());
            cfg.setPassword(settings.password());
            cfg.setDriverClassName(driverClassName());
            cfg.setMaximumPoolSize(settings.maxPoolSize());
            cfg.setMinimumIdle(settings.minIdle());
            cfg.setConnectionTimeout(settings.connectionTimeoutMs());
            cfg.setIdleTimeout(settings.idleTimeoutMs());
            cfg.setMaxLifetime(settings.maxLifetimeMs());
            cfg.setLeakDetectionThreshold(60_000);
            cfg.setPoolName("FlectoneGrowth-" + storageName());

            dataSource = new HikariDataSource(cfg);
            renameLegacyTablesIfNeeded();
            createTables();
            migrateSchemaIfNeeded();
            return testConnection();
        } catch (Exception e) {
            logger.severe("Failed to initialise " + storageName() + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean testConnection() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            logger.warning(storageName() + " connection test failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /** See {@link LegacyTableMigration} - renames this project's tables from their pre-rebrand {@code playergrowth_*} name (or the default {@code flectonegrowth_*} name, if {@link TableNames#prefix()} has just been customised) if an upgrading install still has them, before {@link #createTables()} would otherwise create fresh empty ones under the new name. */
    private void renameLegacyTablesIfNeeded() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            LegacyTableMigration.renameIfNeeded(conn, logger, storageName(), tables.prefix());
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tables.scales() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, scale DOUBLE NOT NULL, valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            ).executeUpdate();

            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tables.genders() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, gender_key VARCHAR(32) NOT NULL DEFAULT 'male', valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            ).executeUpdate();

            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tables.growthTime() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, growth_seconds BIGINT NOT NULL, valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            ).executeUpdate();

            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tables.schemaVersion() + " (" +
                            "id INT PRIMARY KEY, version VARCHAR(32) NOT NULL" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            ).executeUpdate();

            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tables.playtime() + " (" +
                            "uuid VARCHAR(36) NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', " +
                            "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, " +
                            "total_seconds BIGINT NOT NULL DEFAULT 0, sessions INT NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (uuid, server)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            ).executeUpdate();
        }
    }

    /**
     * Runs any pending schema migrations via {@link SchemaVersionStore} - see
     * that class for the shared read/compare/migrate/write orchestration
     * (identical here and in {@code H2Storage}; only {@link #upsertSchemaVersionSql}
     * actually differs between dialects).
     */
    private void migrateSchemaIfNeeded() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            SchemaVersionStore.migrateIfNeeded(conn, type(), tables.schemaVersion(), upsertSchemaVersionSql, pluginVersion, logger, storageName());
        }
    }

    @Override
    public Double getCustomScale(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT scale FROM " + tables.scales() + " WHERE uuid = ? AND valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getDouble("scale"), null,
                logger, storageName(), "getCustomScale");
    }

    @Override
    public boolean setCustomScale(UUID uuid, double scale) {
        return SqlOps.update(dataSource,
                "INSERT INTO " + tables.scales() + " (uuid, scale, valid, updated_at) VALUES (?, ?, 1, NOW()) " +
                        "ON DUPLICATE KEY UPDATE scale = VALUES(scale), valid = 1, updated_at = NOW()",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setDouble(2, scale);
                },
                logger, storageName(), "setCustomScale");
    }

    @Override
    public boolean removeCustomScale(UUID uuid) {
        return SqlOps.update(dataSource,
                "UPDATE " + tables.scales() + " SET valid = 0, updated_at = NOW() WHERE uuid = ? AND valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                logger, storageName(), "removeCustomScale");
    }

    @Override
    public String getGenderKey(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT gender_key FROM " + tables.genders() + " WHERE uuid = ? AND valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getString("gender_key"), null,
                logger, storageName(), "getGenderKey");
    }

    @Override
    public boolean setGenderKey(UUID uuid, String genderKey) {
        return SqlOps.update(dataSource,
                "INSERT INTO " + tables.genders() + " (uuid, gender_key, valid, updated_at) VALUES (?, ?, 1, NOW()) " +
                        "ON DUPLICATE KEY UPDATE gender_key = VALUES(gender_key), valid = 1, updated_at = NOW()",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, genderKey.toLowerCase());
                },
                logger, storageName(), "setGenderKey");
    }

    @Override
    public Long getGrowthTimeSeconds(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT growth_seconds FROM " + tables.growthTime() + " WHERE uuid = ? AND valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getLong("growth_seconds"), null,
                logger, storageName(), "getGrowthTimeSeconds");
    }

    @Override
    public boolean setGrowthTimeSeconds(UUID uuid, long seconds) {
        return SqlOps.update(dataSource,
                "INSERT INTO " + tables.growthTime() + " (uuid, growth_seconds, valid, updated_at) VALUES (?, ?, 1, NOW()) " +
                        "ON DUPLICATE KEY UPDATE growth_seconds = VALUES(growth_seconds), valid = 1, updated_at = NOW()",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setLong(2, seconds);
                },
                logger, storageName(), "setGrowthTimeSeconds");
    }

    @Override
    public PlayTime getPlayTime(UUID uuid, String server) {
        return SqlOps.query(dataSource,
                "SELECT first_seen, last_seen, total_seconds, sessions FROM " + tables.playtime() + " WHERE uuid = ? AND server = ?",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, server);
                },
                rs -> new PlayTime(rs.getLong("first_seen"), rs.getLong("last_seen"),
                        rs.getLong("total_seconds"), rs.getInt("sessions")),
                null,
                logger, storageName(), "getPlayTime");
    }

    @Override
    public boolean recordJoin(UUID uuid, String server, long nowEpochSeconds) {
        return SqlOps.update(dataSource,
                "INSERT INTO " + tables.playtime() + " (uuid, server, first_seen, last_seen, total_seconds, sessions) " +
                        "VALUES (?, ?, ?, ?, 0, 1) " +
                        "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen), sessions = sessions + 1",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, server);
                    stmt.setLong(3, nowEpochSeconds);
                    stmt.setLong(4, nowEpochSeconds);
                },
                logger, storageName(), "recordJoin");
    }

    @Override
    public boolean checkpointPlayTime(UUID uuid, String server, long totalSeconds, long nowEpochSeconds) {
        return SqlOps.update(dataSource,
                "UPDATE " + tables.playtime() + " SET last_seen = ?, total_seconds = ? WHERE uuid = ? AND server = ?",
                stmt -> {
                    stmt.setLong(1, nowEpochSeconds);
                    stmt.setLong(2, totalSeconds);
                    stmt.setString(3, uuid.toString());
                    stmt.setString(4, server);
                },
                logger, storageName(), "checkpointPlayTime");
    }
}
