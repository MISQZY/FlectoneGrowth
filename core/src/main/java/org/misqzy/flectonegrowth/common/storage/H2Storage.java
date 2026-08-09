package org.misqzy.flectonegrowth.common.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.storage.migration.LegacyTableMigration;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaVersionStore;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded, file-based default storage - zero external setup required.
 *
 * <p>Deliberately does <b>not</b> extend {@link AbstractSqlStorage}: H2's
 * default SQL mode does not understand MySQL's {@code ON DUPLICATE KEY
 * UPDATE} syntax (that only works under H2's MySQL compatibility mode,
 * which this plugin does not force on). Using {@code MERGE INTO} here
 * keeps H2 correct under its native dialect instead of silently relying on
 * a compatibility mode that another plugin/driver version could disable.</p>
 */
public final class H2Storage implements Storage {

    private final Logger logger;
    private final File dataFolder;
    private final String pluginVersion;
    private final TableNames tables;
    private final String upsertSchemaVersionSql;
    private HikariDataSource dataSource;

    public H2Storage(Logger logger, File dataFolder, String pluginVersion, String tablePrefix) {
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.pluginVersion = pluginVersion;
        this.tables = new TableNames(tablePrefix);
        this.upsertSchemaVersionSql = "MERGE INTO " + tables.schemaVersion() + " (id, version) KEY(id) VALUES (1, ?)";
    }

    @Override
    public boolean initialize() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.severe("H2 driver not found on the classpath.");
            return false;
        }

        File dbDir = new File(dataFolder, "data");
        //noinspection ResultOfMethodCallIgnored
        dbDir.mkdirs();
        String jdbcUrl = "jdbc:h2:" + new File(dbDir, "database").getAbsolutePath() + ";AUTO_SERVER=TRUE";

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000L);
        // Same safety net AbstractSqlStorage's pool gets - recordJoin below is the one method in
        // this class managing its own PreparedStatements by hand instead of going through SqlOps,
        // so leak detection here specifically covers a future edit reintroducing a leak there.
        cfg.setLeakDetectionThreshold(60_000);
        cfg.setPoolName("FlectoneGrowth-H2");

        try {
            dataSource = new HikariDataSource(cfg);
            renameLegacyTablesIfNeeded();
            createTables();
            migrateSchemaIfNeeded();
            return testConnection();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialise H2 database: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean testConnection() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "H2 connection test failed: " + e.getMessage(), e);
            return false;
        }
    }

    @Override public StorageType type() { return StorageType.H2; }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /** See {@link LegacyTableMigration} - renames this project's tables from their pre-rebrand {@code playergrowth_*} name (or the default {@code flectonegrowth_*} name, if {@link TableNames#prefix()} has just been customised) if an upgrading install still has them, before {@link #createTables()} would otherwise create fresh empty ones under the new name. */
    private void renameLegacyTablesIfNeeded() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            LegacyTableMigration.renameIfNeeded(conn, logger, "H2", tables.prefix());
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Must exist before the FK-bearing tables below reference it.
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.player() + " (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL UNIQUE, " +
                            "username VARCHAR(16))");
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.scales() + " (" +
                            "player_id INT PRIMARY KEY, scale DOUBLE NOT NULL, " +
                            "valid BOOLEAN NOT NULL DEFAULT TRUE, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id))");
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.genders() + " (" +
                            "player_id INT PRIMARY KEY, gender_key VARCHAR(32) NOT NULL DEFAULT 'male', " +
                            "valid BOOLEAN NOT NULL DEFAULT TRUE, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id))");
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.growthTime() + " (" +
                            "player_id INT PRIMARY KEY, growth_seconds BIGINT NOT NULL, " +
                            "valid BOOLEAN NOT NULL DEFAULT TRUE, " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id))");
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.schemaVersion() + " (id INT PRIMARY KEY, version VARCHAR(32) NOT NULL)");
            executeUpdate(conn,
                    "CREATE TABLE IF NOT EXISTS " + tables.playtime() + " (" +
                            "player_id INT NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', " +
                            "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, " +
                            "total_seconds BIGINT NOT NULL DEFAULT 0, sessions INT NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (player_id, server), " +
                            "FOREIGN KEY (player_id) REFERENCES " + tables.player() + "(id))");
        }
    }

    private static void executeUpdate(Connection conn, String sql) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /** Get-or-creates {@code uuid}'s player row - see {@code AbstractSqlStorage}'s twin for why every write needs this first. H2's {@code MERGE INTO ... KEY(uuid)} only ever touches the columns listed, so omitting {@code username} here leaves an already-set value untouched on conflict (see {@link #recordJoin}'s javadoc for the same "listed columns always replaced, others preserved" rule). */
    private void ensurePlayerRow(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "MERGE INTO " + tables.player() + " (uuid) KEY(uuid) VALUES (?)")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Runs any pending schema migrations via {@link SchemaVersionStore} - see
     * that class for the shared read/compare/migrate/write orchestration
     * (identical here and in {@code AbstractSqlStorage}; only
     * {@link #upsertSchemaVersionSql} actually differs between dialects).
     */
    private void migrateSchemaIfNeeded() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            SchemaVersionStore.migrateIfNeeded(conn, StorageType.H2, tables, upsertSchemaVersionSql, pluginVersion, logger, "H2");
        }
    }

    @Override
    public Double getCustomScale(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT s.scale FROM " + tables.scales() + " s JOIN " + tables.player() + " p ON s.player_id = p.id " +
                        "WHERE p.uuid = ? AND s.valid = TRUE",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getDouble("scale"), null,
                logger, "H2", "getCustomScale");
    }

    @Override
    public boolean setCustomScale(UUID uuid, double scale) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "MERGE INTO " + tables.scales() + " (player_id, scale, valid) KEY(player_id) " +
                            "VALUES ((SELECT id FROM " + tables.player() + " WHERE uuid = ?), ?, TRUE)")) {
                stmt.setString(1, uuid.toString());
                stmt.setDouble(2, scale);
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 setCustomScale: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean removeCustomScale(UUID uuid) {
        return SqlOps.update(dataSource,
                "UPDATE " + tables.scales() + " SET valid = FALSE " +
                        "WHERE player_id = (SELECT id FROM " + tables.player() + " WHERE uuid = ?) AND valid = TRUE",
                stmt -> stmt.setString(1, uuid.toString()),
                logger, "H2", "removeCustomScale");
    }

    @Override
    public String getGenderKey(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT g.gender_key FROM " + tables.genders() + " g JOIN " + tables.player() + " p ON g.player_id = p.id " +
                        "WHERE p.uuid = ? AND g.valid = TRUE",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getString("gender_key"), null,
                logger, "H2", "getGenderKey");
    }

    @Override
    public boolean setGenderKey(UUID uuid, String genderKey) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "MERGE INTO " + tables.genders() + " (player_id, gender_key, valid) KEY(player_id) " +
                            "VALUES ((SELECT id FROM " + tables.player() + " WHERE uuid = ?), ?, TRUE)")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, genderKey.toLowerCase(Locale.ROOT));
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 setGenderKey: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Long getGrowthTimeSeconds(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT t.growth_seconds FROM " + tables.growthTime() + " t JOIN " + tables.player() + " p ON t.player_id = p.id " +
                        "WHERE p.uuid = ? AND t.valid = TRUE",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getLong("growth_seconds"), null,
                logger, "H2", "getGrowthTimeSeconds");
    }

    @Override
    public boolean setGrowthTimeSeconds(UUID uuid, long seconds) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "MERGE INTO " + tables.growthTime() + " (player_id, growth_seconds, valid) KEY(player_id) " +
                            "VALUES ((SELECT id FROM " + tables.player() + " WHERE uuid = ?), ?, TRUE)")) {
                stmt.setString(1, uuid.toString());
                stmt.setLong(2, seconds);
                stmt.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 setGrowthTimeSeconds: " + e.getMessage(), e);
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
                logger, "H2", "getPlayTime");
    }

    /**
     * H2's {@code MERGE INTO ... KEY(uuid)} always replaces every listed column
     * unconditionally - unlike MySQL/MariaDB's partial {@code ON DUPLICATE KEY
     * UPDATE}, it can't express "preserve first_seen/total_seconds on conflict"
     * in one statement. So this reads the existing row first (if any) and
     * carries its first_seen/total_seconds/sessions forward into the MERGE,
     * the same get-or-create shape {@code GrowthTimeAssigner.loadInto} already
     * uses at the Java level instead of the SQL level.
     */
    @Override
    public boolean recordJoin(UUID uuid, String server, long nowEpochSeconds) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            PlayTime existing = selectPlayTime(conn, uuid, server);

            long firstSeen = existing != null ? existing.first() : nowEpochSeconds;
            long totalSeconds = existing != null ? existing.total() : 0L;
            int sessions = existing != null ? existing.sessions() + 1 : 1;

            mergePlayTime(conn, uuid, server, firstSeen, nowEpochSeconds, totalSeconds, sessions);
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 recordJoin: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * The {@code AND last_seen <= ?} guard closes a race against a fast quit-then-rejoin - see {@code
     * AbstractSqlStorage}'s twin for the full interleaving this prevents (an independently-dispatched
     * rejoin's {@code recordJoin} landing before this checkpoint's own write, which would otherwise clobber
     * {@code last_seen} back down to the stale quit-time value).
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
                logger, "H2", "checkpointPlayTime");
    }

    /** Same read-then-carry-forward shape as {@link #recordJoin} - H2's {@code MERGE INTO} can't express "add to the existing total" in one statement either. */
    @Override
    public boolean addPlayTime(UUID uuid, String server, long secondsToAdd, long nowEpochSeconds) {
        try (Connection conn = dataSource.getConnection()) {
            ensurePlayerRow(conn, uuid);
            PlayTime existing = selectPlayTime(conn, uuid, server);

            long firstSeen = existing != null ? existing.first() : nowEpochSeconds;
            long totalSeconds = (existing != null ? existing.total() : 0L) + secondsToAdd;
            int sessions = existing != null ? existing.sessions() : 0;

            mergePlayTime(conn, uuid, server, firstSeen, nowEpochSeconds, totalSeconds, sessions);
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 addPlayTime: " + e.getMessage(), e);
            return false;
        }
    }

    private PlayTime selectPlayTime(Connection conn, UUID uuid, String server) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT pt.first_seen, pt.last_seen, pt.total_seconds, pt.sessions FROM " + tables.playtime() + " pt " +
                        "JOIN " + tables.player() + " p ON pt.player_id = p.id WHERE p.uuid = ? AND pt.server = ?")) {
            select.setString(1, uuid.toString());
            select.setString(2, server);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next()
                        ? new PlayTime(rs.getLong("first_seen"), rs.getLong("last_seen"), rs.getLong("total_seconds"), rs.getInt("sessions"))
                        : null;
            }
        }
    }

    private void mergePlayTime(Connection conn, UUID uuid, String server, long firstSeen, long lastSeen,
                                long totalSeconds, int sessions) throws SQLException {
        try (PreparedStatement merge = conn.prepareStatement(
                "MERGE INTO " + tables.playtime() + " (player_id, server, first_seen, last_seen, total_seconds, sessions) KEY(player_id, server) " +
                        "VALUES ((SELECT id FROM " + tables.player() + " WHERE uuid = ?), ?, ?, ?, ?, ?)")) {
            merge.setString(1, uuid.toString());
            merge.setString(2, server);
            merge.setLong(3, firstSeen);
            merge.setLong(4, lastSeen);
            merge.setLong(5, totalSeconds);
            merge.setInt(6, sessions);
            merge.executeUpdate();
        }
    }

    /** See {@code AbstractSqlStorage}'s twin for why any other row currently claiming {@code username} gets cleared first - a Mojang rename otherwise leaves a stale second claim on it forever, ambiguous for {@link #findUuidByUsername}. */
    @Override
    public boolean upsertUsername(UUID uuid, String username) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement clearStale = conn.prepareStatement(
                    "UPDATE " + tables.player() + " SET username = NULL WHERE LOWER(username) = LOWER(?) AND uuid <> ?")) {
                clearStale.setString(1, username);
                clearStale.setString(2, uuid.toString());
                clearStale.executeUpdate();
            }
            try (PreparedStatement merge = conn.prepareStatement(
                    "MERGE INTO " + tables.player() + " (uuid, username) KEY(uuid) VALUES (?, ?)")) {
                merge.setString(1, uuid.toString());
                merge.setString(2, username);
                merge.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "H2 upsertUsername: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String getUsername(UUID uuid) {
        return SqlOps.query(dataSource,
                "SELECT username FROM " + tables.player() + " WHERE uuid = ?",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.getString("username"), null,
                logger, "H2", "getUsername");
    }

    @Override
    public UUID findUuidByUsername(String username) {
        return SqlOps.query(dataSource,
                "SELECT uuid FROM " + tables.player() + " WHERE LOWER(username) = LOWER(?)",
                stmt -> stmt.setString(1, username),
                rs -> UUID.fromString(rs.getString("uuid")), null,
                logger, "H2", "findUuidByUsername");
    }
}
