package org.misqzy.flectonegrowth.common.storage.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * One-time rename of this project's tables to whichever prefix is currently
 * configured (see {@code storage.database.table-prefix},
 * {@link org.misqzy.flectonegrowth.common.storage.TableNames}) from either of
 * the two prefixes an existing install's tables might still carry: the
 * pre-rebrand {@code playergrowth_*} name (the plugin used to be named
 * PlayerGrowth) or this project's own historical default,
 * {@code flectonegrowth_*} (an install that ran with the default before a
 * custom prefix was configured for the first time). Every
 * {@code CREATE TABLE IF NOT EXISTS} in
 * {@link org.misqzy.flectonegrowth.common.storage.AbstractSqlStorage} and
 * {@link org.misqzy.flectonegrowth.common.storage.H2Storage} targets the
 * currently-configured name; without this step, an upgrading install (or one
 * that just changed its table prefix) would find none of its tables under
 * that name, silently create empty ones, and orphan every existing player's
 * data under whichever old name it's still sitting under.
 *
 * <p>{@code ALTER TABLE x RENAME TO y} is one of the few DDL statements
 * whose syntax is identical across MySQL, MariaDB, and H2, so a single
 * dialect-agnostic statement covers all three backends here - no need to
 * branch on {@link org.misqzy.flectonegrowth.common.storage.StorageType}
 * the way the CRUD SQL and {@link SchemaMigrations} steps do elsewhere.
 * Existence is checked via {@link DatabaseMetaData} rather than attempting
 * the rename and swallowing the failure, since a bare "did it fail because
 * the old table is missing, or for some other reason" is not something a
 * caught {@link SQLException} lets this reliably tell apart.</p>
 */
public final class LegacyTableMigration {

    /** Tried in order for each table still missing under the target prefix - the historical pre-rebrand name, then this project's own default. */
    private static final List<String> OLD_PREFIXES = List.of("playergrowth_", "flectonegrowth_");

    private static final List<String> TABLES = List.of(
            "scales", "genders", "growth_time", "schema_version", "playtime");

    private LegacyTableMigration() {}

    public static void renameIfNeeded(Connection conn, Logger logger, String storageName, String targetPrefix) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String table : TABLES) {
            String newName = targetPrefix + table;
            if (tableExists(meta, newName)) continue; // already under the currently-configured name

            for (String oldPrefix : OLD_PREFIXES) {
                if (oldPrefix.equals(targetPrefix)) continue; // renaming a prefix to itself isn't a migration
                String oldName = oldPrefix + table;
                if (!tableExists(meta, oldName)) continue;

                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE " + oldName + " RENAME TO " + newName);
                }
                logger.info(storageName + ": renamed table " + oldName + " to " + newName);
                break; // only one old-prefixed copy of a given table can exist at a time
            }
        }
    }

    /**
     * Tries the name as given first (matches MySQL/MariaDB, which store
     * these unquoted identifiers verbatim in lowercase as created), then
     * uppercased (matches H2, which folds unquoted identifiers to uppercase
     * internally) - {@link DatabaseMetaData#getTables} needs an exact match
     * on the pattern, unlike a DML statement referencing the same unquoted
     * name, which every one of these dialects case-folds for you.
     */
    private static boolean tableExists(DatabaseMetaData meta, String tableName) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, tableName, null)) {
            if (rs.next()) return true;
        }
        try (ResultSet rs = meta.getTables(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
            return rs.next();
        }
    }
}
