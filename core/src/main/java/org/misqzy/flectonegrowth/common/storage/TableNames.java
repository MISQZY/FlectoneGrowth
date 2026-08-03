package org.misqzy.flectonegrowth.common.storage;

/**
 * The five table names {@link AbstractSqlStorage}/{@link H2Storage} read and
 * write, each {@link #prefix} + a fixed suffix - {@code storage.database.table-prefix}
 * in config.yml, default {@code "flectonegrowth"} (this project's table
 * names since the PlayerGrowth -> FlectoneGrowth rebrand, see
 * {@link org.misqzy.flectonegrowth.common.storage.migration.LegacyTableMigration}),
 * so a network sharing one physical database can run more than one
 * FlectoneGrowth instance against it without their tables colliding.
 *
 * <p>The compact constructor normalises {@link #prefix} to always end in
 * {@code _} (or be empty) so an admin configures a plain name like
 * {@code "mynetwork"} - the separator is this class's job, not something to
 * remember to type. Already-underscore-terminated values (including this
 * project's own {@code "flectonegrowth_"} default from before this
 * normalisation existed) pass through unchanged rather than gaining a second
 * underscore, and every other consumer of {@link #prefix} (e.g.
 * {@code LegacyTableMigration}'s plain string concatenation) never needs to
 * know this rule exists.</p>
 */
public record TableNames(String prefix) {

    public TableNames {
        prefix = normalize(prefix);
    }

    private static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        return prefix.endsWith("_") ? prefix : prefix + "_";
    }

    public String scales() { return prefix + "scales"; }
    public String genders() { return prefix + "genders"; }
    public String growthTime() { return prefix + "growth_time"; }
    public String schemaVersion() { return prefix + "schema_version"; }
    public String playtime() { return prefix + "playtime"; }
    public String player() { return prefix + "player"; }
}
