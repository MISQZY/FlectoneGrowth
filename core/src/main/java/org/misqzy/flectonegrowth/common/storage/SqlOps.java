package org.misqzy.flectonegrowth.common.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
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
            logger.log(Level.SEVERE, storageName + " " + op + ": " + e.getMessage(), e);
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
            logger.log(Level.SEVERE, storageName + " " + op + ": " + e.getMessage(), e);
            return false;
        }
    }
}
