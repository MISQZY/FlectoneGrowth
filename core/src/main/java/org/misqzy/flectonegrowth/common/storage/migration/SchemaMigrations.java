package org.misqzy.flectonegrowth.common.storage.migration;

import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.misqzy.flectonegrowth.common.storage.StorageType;
import org.misqzy.flectonegrowth.common.storage.TableNames;

import java.util.List;

/**
 * Registry of every {@link SchemaMigration} this project has ever needed.
 * Each storage implementation ({@code AbstractSqlStorage}, {@code H2Storage})
 * stores the plugin version that last touched its schema in its own
 * {@code flectonegrowth_schema_version} table (one row, id 1) and runs
 * whatever's {@link #pending(String, String)} on connect - see the migration
 * methods on those classes for the actual read/write/run orchestration
 * (kept per-class rather than shared, same reasoning as their CRUD SQL:
 * the version-table upsert syntax is itself dialect-specific - H2's
 * {@code MERGE INTO} vs MySQL/MariaDB's {@code ON DUPLICATE KEY UPDATE} -
 * so there's nothing dialect-agnostic left to usefully share beyond
 * {@link SchemaMigrationRunner}, which just executes whatever
 * already-dialect-resolved statements it's handed).
 */
public final class SchemaMigrations {

    /**
     * The first real step: {@code flectonegrowth_schema_version.version} used
     * to be a plain {@code INT} counter (pre-unification with
     * {@code config.yml}'s semver, see {@code AbstractSqlStorage}/
     * {@code H2Storage}'s {@code readSchemaVersion}). An upgrading install's
     * column is still that {@code INT} - {@code readSchemaVersion} can read
     * it fine ({@code ResultSet.getString} coerces a numeric column to text),
     * but the subsequent write of a real semver like {@code "0.1.3"} would
     * fail against a column that only accepts integers, so it has to be
     * widened before that write happens. A fresh install's {@code CREATE
     * TABLE IF NOT EXISTS} already builds the column as {@code VARCHAR},
     * so this is a no-op there (nothing pending below {@code CURRENT_VERSION}
     * once the version row is stamped directly).
     */
    private static final List<SchemaMigration> STEPS = List.of(
            new SchemaMigration() {
                @Override public String targetVersion() { return "0.1.3"; }

                @Override public List<String> statements(StorageType dialect, TableNames tables) {
                    String schemaVersionTable = tables.schemaVersion();
                    return switch (dialect) {
                        case H2 -> List.of("ALTER TABLE " + schemaVersionTable + " ALTER COLUMN version VARCHAR(32)");
                        case MYSQL, MARIADB -> List.of(
                                "ALTER TABLE " + schemaVersionTable + " MODIFY COLUMN version VARCHAR(32) NOT NULL");
                        case YAML -> List.of(); // no SQL schema to migrate
                    };
                }
            },
            /*
             * network.blocklist (a network-wide list of excluded server ids)
             * was replaced with two per-server booleans, network.per-server
             * and network.include-server (see ConfigMigrations' matching
             * "0.2.1" step and ARCHITECTURE.md "network.blocklist"/
             * "network.per-server" / "network.include-server"). Supporting
             * network.per-server requires flectonegrowth_playtime to key each
             * row by (uuid, server) instead of uuid alone, so a server can
             * keep its own bucket instead of always sharing one network-wide
             * row - existing rows get server = '' (the shared bucket every
             * pre-0.2.1 install already used), which continues to read/write
             * exactly as before when network.per-server stays false (the
             * default). A fresh install's CREATE TABLE IF NOT EXISTS already
             * builds this shape directly.
             */
            new SchemaMigration() {
                @Override public String targetVersion() { return "0.2.1"; }

                @Override public List<String> statements(StorageType dialect, TableNames tables) {
                    String playtimeTable = tables.playtime();
                    return switch (dialect) {
                        case H2 -> List.of(
                                "ALTER TABLE " + playtimeTable + " ADD COLUMN server VARCHAR(64) NOT NULL DEFAULT ''",
                                "ALTER TABLE " + playtimeTable + " DROP PRIMARY KEY",
                                "ALTER TABLE " + playtimeTable + " ADD PRIMARY KEY (uuid, server)");
                        case MYSQL, MARIADB -> List.of(
                                "ALTER TABLE " + playtimeTable + " ADD COLUMN server VARCHAR(64) NOT NULL DEFAULT ''",
                                "ALTER TABLE " + playtimeTable + " DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, server)");
                        case YAML -> List.of(); // no SQL schema to migrate
                    };
                }
            }
    );

    private SchemaMigrations() {}

    /** Every registered step whose {@code targetVersion()} falls in {@code (fromVersion, toVersion]}, in ascending order. */
    public static List<SchemaMigration> pending(String fromVersion, String toVersion) {
        return STEPS.stream()
                .filter(step -> VersionComparator.compare(step.targetVersion(), fromVersion) > 0
                        && VersionComparator.compare(step.targetVersion(), toVersion) <= 0)
                .sorted((a, b) -> VersionComparator.compare(a.targetVersion(), b.targetVersion()))
                .toList();
    }
}
