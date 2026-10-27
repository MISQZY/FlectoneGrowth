package org.misqzy.flectonegrowth.common.storage;

import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.domain.StoredPlayerData;

import javax.sql.DataSource;
import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.misqzy.flectonegrowth.common.log.PluginLog;
import java.util.logging.Logger;

/**
 * Shared query/update execution and error-logging boilerplate for the two
 * SQL-backed {@link Storage} implementations ({@link AbstractSqlStorage},
 * {@link H2Storage}). Only the JDBC ceremony (connection/statement handling,
 * try-with-resources, catch-log-and-return-a-safe-default) is shared here -
 * every SQL string stays in its owning class, since that's exactly where the
 * dialect differences (MySQL's {@code ON DUPLICATE KEY UPDATE} vs. H2's
 * {@code MERGE INTO}, {@code valid = 1} vs {@code valid = TRUE}) live. See
 * {@code H2Storage}'s class javadoc for why the two classes don't share a
 * base class instead.
 */
final class SqlOps {

    private SqlOps() {}

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Runs {@code sql} as a query, maps the first row with {@code mapper}, or returns {@code notFound} if there's no row or the query fails (logged as {@code storageName + " " + op}). */
    static <T> T query(DataSource dataSource, String sql, Binder binder, RowMapper<T> mapper, T notFound,
                        Logger logger, String storageName, String op) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapper.map(rs) : notFound;
            }
        } catch (SQLException e) {
            PluginLog.error(logger, storageName + " " + op, e);
            return notFound;
        }
    }

    /** Runs {@code sql} as an update, returning {@code true} on success or {@code false} if it fails (logged as {@code storageName + " " + op}). */
    static boolean update(DataSource dataSource, String sql, Binder binder,
                           Logger logger, String storageName, String op) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            PluginLog.error(logger, storageName + " " + op, e);
            return false;
        }
    }

    /**
     * One LEFT JOIN query behind {@link Storage#loadStoredData}; identical on every SQL dialect
     * ({@code valid} is read with {@code getBoolean}, which maps both H2's BOOLEAN and MySQL's TINYINT(1)).
     * Returns {@code null} if the query fails, {@link StoredPlayerData#EMPTY} if the player has no row.
     */
    static StoredPlayerData loadStoredData(DataSource dataSource, TableNames tables, UUID uuid, String server,
                                           Logger logger, String storageName) {
        String sql = "SELECT s.scale, s.valid AS scale_valid, g.gender_key, g.valid AS gender_valid, " +
                "t.growth_seconds, t.valid AS growth_valid, " +
                "pt.first_seen, pt.last_seen, pt.total_seconds, pt.sessions " +
                "FROM " + tables.player() + " p " +
                "LEFT JOIN " + tables.scales() + " s ON s.player_id = p.id " +
                "LEFT JOIN " + tables.genders() + " g ON g.player_id = p.id " +
                "LEFT JOIN " + tables.growthTime() + " t ON t.player_id = p.id " +
                "LEFT JOIN " + tables.playtime() + " pt ON pt.player_id = p.id AND pt.server = ? " +
                "WHERE p.uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, server);
            stmt.setString(2, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return StoredPlayerData.EMPTY;
                double scale = rs.getDouble("scale");
                Double customScale = !rs.wasNull() && rs.getBoolean("scale_valid") ? scale : null;
                String genderKey = rs.getString("gender_key");
                if (genderKey != null && !rs.getBoolean("gender_valid")) genderKey = null;
                long growth = rs.getLong("growth_seconds");
                Long growthSeconds = !rs.wasNull() && rs.getBoolean("growth_valid") ? growth : null;
                long first = rs.getLong("first_seen");
                PlayTime playTime = rs.wasNull() ? null
                        : new PlayTime(first, rs.getLong("last_seen"), rs.getLong("total_seconds"), rs.getInt("sessions"));
                return new StoredPlayerData(customScale, genderKey, growthSeconds, playTime);
            }
        } catch (SQLException e) {
            PluginLog.error(logger, storageName + " loadStoredData", e);
            return null;
        }
    }
}
