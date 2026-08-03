package org.misqzy.flectonegrowth.common.storage.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Executes already dialect-resolved SQL statements - no dialect knowledge of its own.
 *
 * <p>Runs the whole batch under one transaction so that on H2 (which supports
 * transactional DDL) a step with several statements (e.g. add-column +
 * drop-key + add-key) can't be left half-applied - a failure rolls back
 * everything this call already ran instead of committing a partial step.
 * MySQL/MariaDB implicitly auto-commit each DDL statement regardless of this
 * setting, so this guarantee is H2-only; MySQL/MariaDB migration steps still
 * need to stay individually re-runnable (e.g. tolerate "duplicate column" on
 * retry) if they ever gain more than one statement.</p>
 */
public final class SchemaMigrationRunner {

    private SchemaMigrationRunner() {}

    public static void run(Connection connection, List<String> statements) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : statements) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }
}
