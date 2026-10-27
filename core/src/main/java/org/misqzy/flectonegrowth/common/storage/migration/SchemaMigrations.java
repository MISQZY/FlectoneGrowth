package org.misqzy.flectonegrowth.common.storage.migration;

import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.misqzy.flectonegrowth.common.storage.StorageType;
import org.misqzy.flectonegrowth.common.storage.TableNames;

import java.util.ArrayList;
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
            },
            /*
             * Introduces a dedicated flectonegrowth_player table (auto-increment
             * id, unique uuid, username) so a player's name can finally be
             * persisted (needed to look someone up by username while they're
             * offline - see GrowthEngine.resolveUuidByUsername) and so
             * scales/genders/growth_time/playtime stop each storing their own
             * copy of the uuid. Those four tables swap their uuid column for a
             * 4-byte player_id FK into the new table.
             *
             * Every uuid already present in any of the four tables gets a
             * player row backfilled with username = NULL - there's nothing to
             * backfill it with (no username was ever persisted before this
             * version), so it's left to fill in passively the next time that
             * player joins (see GrowthEngine.loadPlayer's storage.upsertUsername
             * call). A fresh install's CREATE TABLE IF NOT EXISTS already builds
             * this shape directly.
             *
             * Also drops scales/genders/growth_time's updated_at column - it was
             * written on every insert/update but never read anywhere, dead weight
             * riding along for the ride. playtime never had this column.
             */
            new SchemaMigration() {
                @Override public String targetVersion() { return "0.2.7"; }

                @Override public List<String> statements(StorageType dialect, TableNames tables) {
                    String playerTable = tables.player();
                    String scalesTable = tables.scales();
                    String gendersTable = tables.genders();
                    String growthTimeTable = tables.growthTime();
                    String playtimeTable = tables.playtime();

                    // MySQL/MariaDB's fresh-install player table (AbstractSqlStorage.createTables()) carries an
                    // explicit ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 suffix so it matches every other table it
                    // creates rather than whatever the server's own default happens to be - match that here too.
                    String createPlayerTable = "CREATE TABLE IF NOT EXISTS " + playerTable + " (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL UNIQUE, username VARCHAR(16))"
                            + (dialect == StorageType.H2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

                    List<String> setup = List.of(
                            createPlayerTable,
                            "INSERT INTO " + playerTable + " (uuid) " +
                                    "SELECT DISTINCT uuid FROM (" +
                                    "SELECT uuid FROM " + scalesTable +
                                    " UNION SELECT uuid FROM " + gendersTable +
                                    " UNION SELECT uuid FROM " + growthTimeTable +
                                    " UNION SELECT uuid FROM " + playtimeTable +
                                    ") all_uuids WHERE uuid NOT IN (SELECT uuid FROM " + playerTable + ")");

                    boolean h2 = dialect == StorageType.H2;
                    return switch (dialect) {
                        // updated_at (scales/genders/growth_time only - playtime never had it) is dropped
                        // in the same step: nothing ever read it (write-only column), and this step already
                        // rewrites these three tables' shape, so there's no reason to wait for a separate one.
                        case H2, MYSQL, MARIADB -> concat(setup,
                                swapUuidForPlayerId(scalesTable, playerTable, "player_id", h2),
                                dropUpdatedAt(scalesTable),
                                swapUuidForPlayerId(gendersTable, playerTable, "player_id", h2),
                                dropUpdatedAt(gendersTable),
                                swapUuidForPlayerId(growthTimeTable, playerTable, "player_id", h2),
                                dropUpdatedAt(growthTimeTable),
                                swapUuidForPlayerId(playtimeTable, playerTable, "player_id, server", h2));
                        case YAML -> List.of(); // no SQL schema to migrate - see YamlStorage's own "players" section
                    };
                }

                private List<String> dropUpdatedAt(String table) {
                    return List.of("ALTER TABLE " + table + " DROP COLUMN updated_at");
                }

                /** {@code newPrimaryKey} is {@code "player_id"} for the four single-key tables, {@code "player_id, server"} for playtime's composite key. */
                private List<String> swapUuidForPlayerId(String table, String playerTable, String newPrimaryKey, boolean h2) {
                    List<String> statements = new ArrayList<>(List.of(
                            "ALTER TABLE " + table + " ADD COLUMN player_id INT",
                            "UPDATE " + table + " SET player_id = (SELECT id FROM " + playerTable + " WHERE " + playerTable + ".uuid = " + table + ".uuid)"));
                    if (h2) {
                        statements.add("ALTER TABLE " + table + " ALTER COLUMN player_id SET NOT NULL");
                        statements.add("ALTER TABLE " + table + " DROP PRIMARY KEY");
                        statements.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + newPrimaryKey + ")");
                    } else {
                        statements.add("ALTER TABLE " + table + " MODIFY COLUMN player_id INT NOT NULL");
                        statements.add("ALTER TABLE " + table + " DROP PRIMARY KEY, ADD PRIMARY KEY (" + newPrimaryKey + ")");
                    }
                    statements.add("ALTER TABLE " + table + " ADD CONSTRAINT fk_" + table + "_player FOREIGN KEY (player_id) REFERENCES " + playerTable + "(id)");
                    statements.add("ALTER TABLE " + table + " DROP COLUMN uuid");
                    return statements;
                }

                @SafeVarargs
                private List<String> concat(List<String> first, List<String>... rest) {
                    List<String> combined = new ArrayList<>(first);
                    for (List<String> more : rest) combined.addAll(more);
                    return combined;
                }
            },
            /*
             * Username lookups (GrowthEngine.loadPlayer's upsertUsername on every join, and
             * findUuidByUsername) used LOWER(username) = LOWER(?), which can't use any index:
             * a full table scan of the player table per join. They now compare "username = ?" directly,
             * which is only equivalent if the column itself compares case-insensitively:
             *  - H2: the column becomes VARCHAR_IGNORECASE (case-insensitive =, indexable);
             *  - MySQL/MariaDB: the column is already utf8mb4_unicode_ci, but that is pinned here
             *    explicitly in case a table predates that suffix.
             * Both then get a plain (non-unique: legacy rows may hold case-variant duplicates) index.
             * Every statement is safe to re-run (the runner tolerates MySQL's duplicate-index error).
             */
            new SchemaMigration() {
                @Override public String targetVersion() { return "0.2.11"; }

                @Override public List<String> statements(StorageType dialect, TableNames tables) {
                    String playerTable = tables.player();
                    return switch (dialect) {
                        case H2 -> List.of(
                                "ALTER TABLE " + playerTable + " ALTER COLUMN username VARCHAR_IGNORECASE(16)",
                                "CREATE INDEX IF NOT EXISTS idx_" + playerTable + "_username ON " + playerTable + " (username)");
                        case MYSQL, MARIADB -> List.of(
                                "ALTER TABLE " + playerTable + " MODIFY COLUMN username VARCHAR(16) " +
                                        "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL",
                                "CREATE INDEX idx_player_username ON " + playerTable + " (username)");
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
