package org.misqzy.flectonegrowth.common.storage.migration;

import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.misqzy.flectonegrowth.common.storage.StorageType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * The "read current schema version -> run every pending {@link SchemaMigration}
 * for this dialect -> write the new version" orchestration shared by every
 * SQL-backed {@code Storage} implementation - previously duplicated near-
 * verbatim between {@code AbstractSqlStorage} (MySQL/MariaDB) and
 * {@code H2Storage}, since neither could extend the other (their CRUD SQL
 * genuinely differs - H2's default mode doesn't support {@code ON DUPLICATE
 * KEY UPDATE}). The version-table {@code SELECT} is identical everywhere;
 * only the upsert statement is dialect-specific (H2's {@code MERGE INTO} vs
 * MySQL/MariaDB's {@code INSERT ... ON DUPLICATE KEY UPDATE}), so that's the
 * one thing each caller still supplies.
 */
public final class SchemaVersionStore {

    /** Stamped when the version row is missing/unreadable, e.g. a pre-unification install whose column still held a bare int. */
    private static final String UNKNOWN_VERSION = "0.0.0";

    private SchemaVersionStore() {}

    /**
     * Runs every {@link SchemaMigration} pending for {@code dialect} between
     * the version currently stamped in the database and {@code pluginVersion},
     * then stamps {@code pluginVersion}. A brand new install's tables already
     * match the running plugin's shape (just built fresh by the caller's own
     * {@code CREATE TABLE IF NOT EXISTS}), so a missing version row is
     * stamped with {@code pluginVersion} immediately rather than treated as
     * "run every migration ever written".
     *
     * @param schemaVersionTable the (possibly custom-prefixed, see {@code TableNames}) name of the
     *                           single-row version table {@code upsertSql} itself already targets
     */
    public static void migrateIfNeeded(Connection conn, StorageType dialect, String schemaVersionTable, String upsertSql,
                                        String pluginVersion, Logger logger, String storageName) throws SQLException {
        String current = readOrInitVersion(conn, schemaVersionTable, upsertSql, pluginVersion);
        if (VersionComparator.compare(current, pluginVersion) >= 0) return;

        for (SchemaMigration step : SchemaMigrations.pending(current, pluginVersion)) {
            SchemaMigrationRunner.run(conn, step.statements(dialect));
        }
        writeVersion(conn, upsertSql, pluginVersion);
        logger.info(storageName + " schema migrated from version " + current + " to " + pluginVersion + ".");
    }

    private static String readOrInitVersion(Connection conn, String schemaVersionTable, String upsertSql, String pluginVersion) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT version FROM " + schemaVersionTable + " WHERE id = 1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                // getString() reads a legacy INT-typed column (the pre-unification "1") as text too;
                // a bare integer isn't a real semver, so treat it as unknown rather than let
                // VersionComparator misread e.g. "1" as newer than "0.1.2".
                String stored = rs.getString("version");
                return stored != null && stored.indexOf('.') >= 0 ? stored : UNKNOWN_VERSION;
            }
        }
        writeVersion(conn, upsertSql, pluginVersion);
        return pluginVersion;
    }

    private static void writeVersion(Connection conn, String upsertSql, String version) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            stmt.setString(1, version);
            stmt.executeUpdate();
        }
    }
}
