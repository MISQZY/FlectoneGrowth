package org.misqzy.flectonegrowth.common.storage.migration;

import org.misqzy.flectonegrowth.common.config.migration.ConfigMigrationStep;
import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.misqzy.flectonegrowth.common.storage.AbstractSqlStorage;
import org.misqzy.flectonegrowth.common.storage.H2Storage;
import org.misqzy.flectonegrowth.common.storage.StorageType;
import org.misqzy.flectonegrowth.common.storage.TableNames;

import java.util.List;

/**
 * One incremental database schema change (an {@code ALTER TABLE}, a data
 * copy between tables, etc.) - the SQL analogue of
 * {@link ConfigMigrationStep}.
 *
 * <p>Mirrors FlectonePulse's {@code sqls/migrations/&lt;version&gt;.sql}
 * files (one script per version bump that needed one, applied in order and
 * gated by a version stored in the database itself - their {@code fp_version}
 * table) - adapted for this project's smaller, simpler schema and its
 * existing per-dialect split ({@link AbstractSqlStorage}
 * for MySQL/MariaDB, {@link H2Storage}
 * independently for H2 - see {@code docs/ARCHITECTURE.md} "Bugs fixed" #1
 * for why those two are never collapsed together).
 *
 * <p>{@code targetVersion()} is the plugin's own semver (the same
 * {@code config.yml} {@code version} string {@link ConfigMigrationStep}
 * targets), compared with
 * {@link VersionComparator} -
 * not an independent monotonic counter. The stored
 * {@code flectonegrowth_schema_version} row is meant to read as "this
 * database's shape matches what plugin version X built", so it stays
 * legible next to {@code config.yml}'s own {@code version} instead of
 * tracking a second, unrelated number.</p>
 */
public interface SchemaMigration {

    /** The plugin version (semver, e.g. {@code "0.1.3"}) this step upgrades the database to. */
    String targetVersion();

    /** Dialect-specific statements to run when upgrading to {@link #targetVersion()}, in order, against {@code tables}' (possibly custom-prefixed) table names. */
    List<String> statements(StorageType dialect, TableNames tables);
}
