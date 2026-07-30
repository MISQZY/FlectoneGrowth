package org.misqzy.flectonegrowth.bukkit.config;

import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Carries an upgrading install's whole data folder over from the plugin's
 * pre-rebrand name. Bukkit derives {@link JavaPlugin#getDataFolder()} from
 * {@code plugin.yml}'s {@code name} (now {@code FlectoneGrowth}), which used
 * to be {@code PlayerGrowth} - to the server, that's simply a different
 * folder, so without this step an upgrading server would silently start
 * fresh in a new {@code plugins/FlectoneGrowth/} folder while every existing
 * {@code config.yml}/{@code gender.yml}/{@code integrations.yml}, bundled
 * translation edits, and (for the H2/YAML storage defaults) the actual
 * player data under {@code data/} stayed behind, untouched, in
 * {@code plugins/PlayerGrowth/}.
 *
 * <p>Only acts the first time: skipped once {@code getDataFolder()} already
 * exists, whether because this has already run on a previous startup or
 * because it's a genuinely fresh install that happens to share a
 * {@code plugins/} folder with an unrelated leftover directory.</p>
 */
@RequiredArgsConstructor
public final class LegacyDataFolderMigrator {

    private static final String LEGACY_FOLDER_NAME = "PlayerGrowth";

    private final JavaPlugin plugin;

    public void migrateIfNeeded() {
        File dataFolder = plugin.getDataFolder();
        if (dataFolder.exists()) return;

        File parent = dataFolder.getParentFile();
        if (parent == null) return;

        File legacyFolder = new File(parent, LEGACY_FOLDER_NAME);
        if (!legacyFolder.isDirectory()) return;

        Logger logger = plugin.getLogger();
        if (legacyFolder.renameTo(dataFolder)) {
            logger.info("Migrated data folder from '" + LEGACY_FOLDER_NAME
                    + "' to '" + dataFolder.getName() + "' (PlayerGrowth -> FlectoneGrowth rename) - "
                    + "existing config and player data carried over.");
        } else {
            logger.warning("Found a legacy '" + LEGACY_FOLDER_NAME + "' data folder next to '"
                    + dataFolder.getName() + "' but could not rename it automatically. "
                    + "Move/rename it to '" + dataFolder.getName() + "' by hand, or the plugin will start fresh.");
        }
    }
}
