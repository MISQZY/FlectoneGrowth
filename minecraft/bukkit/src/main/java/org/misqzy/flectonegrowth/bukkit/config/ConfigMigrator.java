package org.misqzy.flectonegrowth.bukkit.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.migration.ConfigMigrations;
import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.misqzy.flectonegrowth.common.config.migration.YamlNodeOps;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Migrates every bundled resource file (config.yml, gender.yml,
 * integrations.yml, both locale files) forward when config.yml's {@code
 * version} is stale, by transforming each on-disk composed tree in place
 * rather than discarding it - see {@link ConfigMigrations} for why and how.
 * All five files share config.yml's version rather than each tracking their
 * own; there's only one bundled "resource pack" version to be behind or
 * caught up with.
 *
 * <p>{@code version} is the plugin's own semver (e.g. {@code "0.1.1"}),
 * token-expanded into the bundled config.yml at build time - not a separate
 * incrementing counter. This means the on-disk copy also doubles as "the
 * plugin build that generated/last touched this file" (what a dedicated
 * {@code ConfigVersionStamper} used to do as a second mechanism, before the
 * two fields were merged into one): every release runs this migration path
 * at least once, which is harmless since {@link ConfigMigrations#apply}'s
 * merge step is idempotent when nothing actually changed shape.</p>
 *
 * <p>Comments survive a rewrite: {@link #readBundled}/{@link #readDisk}
 * compose a SnakeYAML {@link Node} tree (not a plain {@code Map}) with
 * {@link LoaderOptions#setProcessComments(boolean)} on, and {@link #write}
 * serialises that same tree back with {@link DumperOptions#setProcessComments}
 * on - both {@link ConfigMigrationStep}s and {@link ConfigMigrations#apply}'s
 * merge pass mutate the tree via {@link YamlNodeOps} rather than a {@code Map},
 * so an admin's explanatory comments in the bundled default (and anything they
 * added themselves) aren't lost on a version bump anymore, only the value of
 * whatever an explicit migration step intentionally changes.</p>
 *
 * <p>Localization files go through the exact same merge as config.yml
 * (closed key set, safe to merge) rather than being replaced outright - an
 * admin's edited translation for an existing key now survives an upgrade,
 * at the cost that a wording/color-tag improvement to that same key in a
 * new release won't reach an already-customised install automatically.</p>
 */
public final class ConfigMigrator {

    /** Has a fixed, closed key set and admin-customisable values - safe to auto-merge any bundled key the disk copy is missing. */
    private static final String MAIN_CONFIG_RESOURCE = "config.yml";

    /** Closed key set (one boolean per integration/submodule) - safe to auto-merge, same as config.yml. */
    private static final String INTEGRATIONS_RESOURCE = "integrations.yml";

    /** Bundled translations - closed key set, merged the same way as config.yml so admin edits to existing keys survive an upgrade. */
    private static final List<String> LOCALIZATION_RESOURCES = List.of(
            "localizations/messages_en.yml", "localizations/messages_ru.yml");

    /** Has an open, user-owned {@code types} section - only explicit steps run against it, never a blind merge. */
    private static final String GENDER_RESOURCE = "gender.yml";

    /** No {@code version} on disk at all (an install from before this field existed) - always stale. */
    private static final String UNKNOWN_VERSION = "0.0.0";

    /** Backup suffix timestamp, e.g. {@code 2026-07-29_14-05} - appended as {@code <name>.yml.backup_<timestamp>}. */
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private final JavaPlugin plugin;
    private final Yaml composer;
    private final Yaml serializer;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;

        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setProcessComments(true);
        this.composer = new Yaml(loaderOptions);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setProcessComments(true);
        this.serializer = new Yaml(dumperOptions);
    }

    public void migrateIfNeeded() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        String bundledVersion = readVersion(readBundled("config.yml"));
        String onDiskVersion = readVersion(readDisk(configFile));
        if (VersionComparator.compare(onDiskVersion, bundledVersion) >= 0) return;

        migrateOne(MAIN_CONFIG_RESOURCE, onDiskVersion, bundledVersion, true);
        for (String resource : LOCALIZATION_RESOURCES) {
            migrateOne(resource, onDiskVersion, bundledVersion, true);
        }
        migrateOne(GENDER_RESOURCE, onDiskVersion, bundledVersion, false);
        migrateOne(INTEGRATIONS_RESOURCE, onDiskVersion, bundledVersion, true);

        plugin.getLogger().warning("Migrated FlectoneGrowth's config files from version " + onDiskVersion
                + " to " + bundledVersion + ". Every file was backed up (<name>.yml.backup_<date_hh_mm>) first,"
                + " with custom values (and comments) carried over automatically.");
    }

    private void migrateOne(String resourceName, String fromVersion, String toVersion, boolean mergeMissingKeys) {
        File target = new File(plugin.getDataFolder(), resourceName);
        if (!target.exists()) return;

        MappingNode bundled = readBundled(resourceName);
        if (bundled == null) return; // not actually bundled under this name - nothing to migrate against

        MappingNode disk = readDisk(target);
        if (disk == null) return; // empty/unparseable on-disk file - leave it alone rather than guessing

        backup(target);
        ConfigMigrations.apply(resourceName, disk, bundled, fromVersion, toVersion, mergeMissingKeys);
        if (MAIN_CONFIG_RESOURCE.equals(resourceName)) {
            YamlNodeOps.putScalarString(disk, "version", toVersion);
        }
        write(target, disk);
    }

    /** Composes the bundled jar copy of {@code resourceName}, or {@code null} if it isn't actually bundled (or fails to read, logged as a warning - that shouldn't happen for a resource actually packaged in this jar). */
    private MappingNode readBundled(String resourceName) {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) return null;
            return asMapping(composer.compose(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read bundled " + resourceName + " for migration: " + e.getMessage());
            return null;
        }
    }

    /** Composes the on-disk copy of {@code file}, or {@code null} if it's empty/not a mapping/unreadable (the latter logged as a warning, so a genuine I/O failure isn't indistinguishable from "nothing to migrate"). */
    private MappingNode readDisk(File file) {
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            return asMapping(composer.compose(reader));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read " + file.getName() + " for migration: " + e.getMessage());
            return null;
        }
    }

    private static MappingNode asMapping(Node node) {
        return node instanceof MappingNode mapping ? mapping : null;
    }

    private String readVersion(MappingNode map) {
        String version = map != null ? YamlNodeOps.getScalarString(map, "version") : null;
        return version != null ? version : UNKNOWN_VERSION;
    }

    private void backup(File target) {
        File backup = new File(target.getParentFile(), target.getName() + ".backup_" + LocalDateTime.now().format(BACKUP_TIMESTAMP));
        try {
            Files.copy(target.toPath(), backup.toPath());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to back up " + target.getName() + " before migration: " + e.getMessage());
        }
    }

    private void write(File target, MappingNode content) {
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
            serializer.serialize(content, out);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write migrated " + target.getName() + ": " + e.getMessage());
        }
    }
}
