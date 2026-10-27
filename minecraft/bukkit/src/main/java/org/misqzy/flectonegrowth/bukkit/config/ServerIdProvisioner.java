package org.misqzy.flectonegrowth.bukkit.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseServerIdResolver;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves this server's top-level {@code server} identifier, generating and
 * persisting a random UUID the first time the field is blank (e.g. a fresh
 * install) so the value stays stable across restarts instead of being
 * re-derived - and re-randomised - on every startup.
 *
 * <p>{@code server} is a top-level config.yml key (mirroring FlectonePulse's
 * own config shape, whose {@code Config} record has {@code server} as its
 * very first field) - it used to live nested under {@code network.server};
 * an install upgrading from that shape has its existing value carried
 * forward by a {@code ConfigMigrationStep}, not by this class.</p>
 *
 * <p>Writes the generated value back into {@code config.yml} with a
 * targeted line replacement rather than a full SnakeYAML re-dump, so a
 * brand new install doesn't immediately lose all of the bundled file's
 * comments the very first time it starts (same tradeoff as
 * {@link ConfigMigrator}, which only rewrites the whole file on an actual
 * {@code version} bump, not on every run).</p>
 */
public final class ServerIdProvisioner {

    private ServerIdProvisioner() {}

    public static String resolveOrGenerate(JavaPlugin plugin, ConfigView mainConfig) {
        String existing = mainConfig.getString("server", "");
        if (existing != null && !existing.isBlank()) return existing;

        String generated = UUID.randomUUID().toString();
        persist(plugin, generated);
        return generated;
    }

    /**
     * Writes {@code value} into {@code config.yml}'s {@code server} if it
     * differs from {@code current} - a no-op otherwise. Used to make
     * FlectonePulse's server id (see {@link FlectonePulseServerIdResolver})
     * stick permanently in FlectoneGrowth's own config once found, instead of
     * only overriding it in memory for the running session and re-resolving
     * it (redundantly, but harmlessly) on every future restart.
     */
    public static void persistIfChanged(JavaPlugin plugin, String current, String value) {
        if (value.equals(current)) return;
        persist(plugin, value);
    }

    /** Replaces an existing top-level {@code server:} line if there is one, otherwise inserts one at the top. */
    private static void persist(JavaPlugin plugin, String serverId) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            List<String> updated = new ArrayList<>(lines.size() + 1);
            boolean replaced = false;

            for (String line : lines) {
                if (!replaced && line.matches("^server:\\s*.*$")) {
                    updated.add("server: '" + serverId + "'");
                    replaced = true;
                } else {
                    updated.add(line);
                }
            }
            if (!replaced) updated.add(0, "server: '" + serverId + "'");

            Files.write(configFile.toPath(), updated, StandardCharsets.UTF_8);
            plugin.getLogger().info("Saved server id \"" + serverId + "\" to config.yml");
        } catch (IOException e) {
            org.misqzy.flectonegrowth.common.log.PluginLog.error(plugin.getLogger(), "Saving server id to config.yml", e);
        }
    }
}
