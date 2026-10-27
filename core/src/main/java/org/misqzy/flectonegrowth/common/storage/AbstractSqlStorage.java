package org.misqzy.flectonegrowth.common.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.domain.StoredPlayerData;
import org.misqzy.flectonegrowth.common.storage.migration.LegacyTableMigration;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaVersionStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
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
            logger.log(Level.SEVERE, "Failed to initialise " + storageName() + ": " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean testConnection() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            logger.log(Level.WARNING, storageName() + " connection test failed: " + e.getMessage(), e);
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

    private static final String TABLE_SUFFIX = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Must exist before the FK-bearing tables below reference it.
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.player() + " (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL UNIQUE, " +
                            "username VARCHAR(16), INDEX idx_player_username (username)" + TABLE_SUFFIX);

            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.scales() + " (" +
                            "player_id INT PRIMARY KEY, scale DOUBLE NOT NULL, valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id)" + TABLE_SUFFIX);

            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.genders() + " (" +
                            "player_id INT PRIMARY KEY, gender_key VARCHAR(32) NOT NULL DEFAULT 'male', valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id)" + TABLE_SUFFIX);

            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.growthTime() + " (" +
                            "player_id INT PRIMARY KEY, growth_seconds BIGINT NOT NULL, valid TINYINT(1) NOT NULL DEFAULT 1, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id)" + TABLE_SUFFIX);

            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.schemaVersion() + " (" +
                            "id INT PRIMARY KEY, version VARCHAR(32) NOT NULL" + TABLE_SUFFIX);

            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.playtime() + " (" +
                            "player_id INT NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', " +
                            "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, " +
                            "total_seconds BIGINT NOT NULL DEFAULT 0, sessions INT NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (player_id, server), " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id)" + TABLE_SUFFIX);
        }
    }

    /** Get-or-creates {@code uuid}'s player row (never touching {@code username} on conflict, so it can't clobber a value {@link #upsertUsername} already set) - every write below needs this first, since resolving {@code player_id} via subquery would otherwise silently insert zero rows for a never-before-seen player. */
    private void ensurePlayerRow(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT IGNORE INTO " + tables.player() + " (uuid) VALUES (?)")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    private static void executeUpdate(Connection conn, String sql) throws SQLException {
        try (var statement = conn.prepareStatement(sql)) {
            statement.executeUpdate();
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
            SchemaVersionStore.migrateIfNeeded(conn, type(), tables, upsertSchemaVersionSql, pluginVersion, logger, storageName());
        }
    }

    @Override
    public StoredPlayerData loadStoredData(UUID uuid, String server) {
        return SqlOps.loadStoredData(dataSource, tables, uuid, server, logger, storageName());
    }

    @Override
    public Double getCustomScale(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT s.scale FROM " + tables.scales() + " s JOIN " + tables.player() + " p ON s.player_id = p.id " +
                        "WHERE p.uuid = ? AND s.valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getDouble("scale"), null,
                logger, storageName(), "getCustomScale");
    }

    @Override
    public boolean setCustomScale(UUID uuid, double scale) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + tables.scales() + " (player_id, scale, valid) " +
                            "SELECT id, ?, 1 FROM " + tables.player() + " WHERE uuid = ? " +
                            "ON DUPLICATE KEY UPDATE scale = VALUES(scale), valid = 1")) {
                stmt.setDouble(1, scale);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " setCustomScale: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean removeCustomScale(UUID uuid) {
        return SqlOps.update(dataSource,
                "UPDATE " + tables.scales() + " SET valid = 0 " +
                        "WHERE player_id = (SELECT id FROM " + tables.player() + " WHERE uuid = ?) AND valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                logger, storageName(), "removeCustomScale");
    }

    @Override
    public String getGenderKey(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT g.gender_key FROM " + tables.genders() + " g JOIN " + tables.player() + " p ON g.player_id = p.id " +
                        "WHERE p.uuid = ? AND g.valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getString("gender_key"), null,
                logger, storageName(), "getGenderKey");
    }

    @Override
    public boolean setGenderKey(UUID uuid, String genderKey) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + tables.genders() + " (player_id, gender_key, valid) " +
                            "SELECT id, ?, 1 FROM " + tables.player() + " WHERE uuid = ? " +
                            "ON DUPLICATE KEY UPDATE gender_key = VALUES(gender_key), valid = 1")) {
                stmt.setString(1, genderKey.toLowerCase(Locale.ROOT));
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " setGenderKey: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Long getGrowthTimeSeconds(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT t.growth_seconds FROM " + tables.growthTime() + " t JOIN " + tables.player() + " p ON t.player_id = p.id " +
                        "WHERE p.uuid = ? AND t.valid = 1",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getLong("growth_seconds"), null,
                logger, storageName(), "getGrowthTimeSeconds");
    }

    @Override
    public boolean setGrowthTimeSeconds(UUID uuid, long seconds) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + tables.growthTime() + " (player_id, growth_seconds, valid) " +
                            "SELECT id, ?, 1 FROM " + tables.player() + " WHERE uuid = ? " +
                            "ON DUPLICATE KEY UPDATE growth_seconds = VALUES(growth_seconds), valid = 1")) {
                stmt.setLong(1, seconds);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " setGrowthTimeSeconds: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public PlayTime getPlayTime(UUID uuid, String server) {
        return SqlOps.query(dataSource,
                "SELECT pt.first_seen, pt.last_seen, pt.total_seconds, pt.sessions FROM " + tables.playtime() + " pt " +
                        "JOIN " + tables.player() + " p ON pt.player_id = p.id WHERE p.uuid = ? AND pt.server = ?",
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
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + tables.playtime() + " (player_id, server, first_seen, last_seen, total_seconds, sessions) " +
                            "SELECT id, ?, ?, ?, 0, 1 FROM " + tables.player() + " WHERE uuid = ? " +
                            "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen), sessions = sessions + 1")) {
                stmt.setString(1, server);
                stmt.setLong(2, nowEpochSeconds);
                stmt.setLong(3, nowEpochSeconds);
                stmt.setString(4, uuid.toString());
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " recordJoin: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * The {@code AND last_seen <= ?} guard closes a race against a fast quit-then-rejoin: {@link
     * #recordJoin}/this method both dispatch their DB write via independent async tasks with no ordering
     * guarantee between them. If a rejoin's {@code recordJoin} (which bumps {@code last_seen} to the rejoin's
     * own, later timestamp) lands before this checkpoint's own write (captured at the earlier quit moment),
     * an unconditional {@code UPDATE} would clobber {@code last_seen} back down to the stale quit-time value
     * - this guard makes that {@code UPDATE} affect zero rows instead, since the row's current {@code
     * last_seen} being newer than {@code nowEpochSeconds} is exactly the signal that a later event already
     * superseded this checkpoint.
     */
    @Override
    public boolean checkpointPlayTime(UUID uuid, String server, long totalSeconds, long nowEpochSeconds) {
        return SqlOps.update(dataSource,
                "UPDATE " + tables.playtime() + " SET last_seen = ?, total_seconds = ? " +
                        "WHERE player_id = (SELECT id FROM " + tables.player() + " WHERE uuid = ?) AND server = ? AND last_seen <= ?",
                stmt -> {
                    stmt.setLong(1, nowEpochSeconds);
                    stmt.setLong(2, totalSeconds);
                    stmt.setString(3, uuid.toString());
                    stmt.setString(4, server);
                    stmt.setLong(5, nowEpochSeconds);
                },
                logger, storageName(), "checkpointPlayTime");
    }

    @Override
    public boolean addPlayTime(UUID uuid, String server, long secondsToAdd, long nowEpochSeconds) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + tables.playtime() + " (player_id, server, first_seen, last_seen, total_seconds, sessions) " +
                            "SELECT id, ?, ?, ?, ?, 0 FROM " + tables.player() + " WHERE uuid = ? " +
                            "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen), total_seconds = total_seconds + VALUES(total_seconds)")) {
                stmt.setString(1, server);
                stmt.setLong(2, nowEpochSeconds);
                stmt.setLong(3, nowEpochSeconds);
                stmt.setLong(4, secondsToAdd);
                stmt.setString(5, uuid.toString());
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " addPlayTime: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * {@code username = ?} is already case-insensitive here (the column is utf8mb4_unicode_ci - see the
     * 0.2.11 schema migration, which also pins that and adds the index these lookups rely on).
     *
     * <p>Minecraft usernames aren't permanently tied to a uuid - Mojang lets a
     * player rename, and whoever's row still carries their old name would
     * otherwise sit there indefinitely as a second, stale claim on it (this
     * class never deletes a player row). With two rows both matching a name,
     * {@link #findUuidByUsername} would have no way to prefer the actual
     * current owner over whoever used to go by that name - so any other
     * row currently claiming {@code username} gets cleared to {@code NULL}
     * first, before this uuid claims it.
     */
    @Override
    public boolean upsertUsername(UUID uuid, String username) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement clearStale = conn.prepareStatement(
                    "UPDATE " + tables.player() + " SET username = NULL WHERE username = ? AND uuid <> ?")) {
                clearStale.setString(1, username);
                clearStale.setString(2, uuid.toString());
                clearStale.executeUpdate();
            }
            try (PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO " + tables.player() + " (uuid, username) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE username = VALUES(username)")) {
                upsert.setString(1, uuid.toString());
                upsert.setString(2, username);
                upsert.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, storageName() + " upsertUsername: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String getUsername(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT username FROM " + tables.player() + " WHERE uuid = ?",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getString("username"), null,
                logger, storageName(), "getUsername");
    }

    @Override
    public UUID findUuidByUsername(String username) {
        return SqlOps.query(dataSource,
                "SELECT uuid FROM " + tables.player() + " WHERE username = ?",
                stmt -> stmt.setString(1, username),
                rs -> UUID.fromString(rs.getString("uuid")), null,
                logger, storageName(), "findUuidByUsername");
    }
}
