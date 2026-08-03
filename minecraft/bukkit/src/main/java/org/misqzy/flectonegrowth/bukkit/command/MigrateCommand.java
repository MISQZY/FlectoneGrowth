package org.misqzy.flectonegrowth.bukkit.command;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthPlugin;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;

import java.util.List;
import java.util.Map;

/**
 * One-off admin tooling to seed this plugin's own playtime tracking from
 * Minecraft's built-in per-player {@link Statistic#PLAY_ONE_MINUTE}
 * statistic - useful when turning {@code network.sync-enabled} on for an
 * existing install, since that mode reads from this plugin's own DB-backed
 * total (see {@code GrowthEngine.playedSeconds}) instead of the vanilla
 * stat directly, and a player who never triggered a tracked join has
 * nothing there yet.
 *
 * <p>Iterates every player {@link Bukkit#getOfflinePlayers()} knows about
 * (online and offline - the vanilla stat itself has no per-world scoping to
 * narrow that down further) and adds their played time on top of any
 * existing record via {@link GrowthEngine#addPlaytimeSeconds}, so running
 * it twice only double-counts, never loses data - still meant to be run
 * once per install, not routinely. Reading thousands of offline players'
 * stat files and writing them to storage can take a while, so the whole
 * thing runs off the main thread.</p>
 *
 * <p>Only meaningful when {@code network.sync-enabled} is on: growth math
 * otherwise reads the vanilla statistic directly, never the DB-backed total
 * this command writes into (see {@code GrowthEngine.playedSeconds}), so the
 * migrated data would silently have no visible effect - that case gets its
 * own warning in the completion message instead of reporting a misleading
 * "success". When sync is on, any player already online at the time this
 * runs would otherwise keep applying their stale, pre-migration cached
 * total until their next relog, so every online player's growth state is
 * explicitly reloaded afterward (mirrors {@link FlectoneGrowthPlugin#reload}'s
 * own post-reload refresh).</p>
 */
@RequiredArgsConstructor
public final class MigrateCommand {

    private static final long TICKS_PER_SECOND = 20L;

    private final FlectoneGrowthPlugin plugin;
    private final FlectoneGrowthCore core;

    @Command("flectonegrowth|fg migrate playtime")
    @CommandDescription("Import every known player's vanilla playtime statistic into FlectoneGrowth's own tracking.")
    @Permission("flectonegrowth.admin.migrate")
    public void migratePlaytime(CommandSender sender) {
        OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();

        FlectoneGrowthMessages.send(core, sender, "admin.migrate-playtime-started",
                Map.of("count", String.valueOf(offlinePlayers.length)));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GrowthEngine engine = core.growthEngine();
            int migrated = 0;
            int skipped = 0;
            for (OfflinePlayer offlinePlayer : offlinePlayers) {
                long seconds = offlinePlayer.getStatistic(Statistic.PLAY_ONE_MINUTE) / TICKS_PER_SECOND;
                if (engine.addPlaytimeSeconds(offlinePlayer.getUniqueId(), seconds)) migrated++;
                else skipped++;
            }

            int finalMigrated = migrated;
            int finalSkipped = skipped;
            Bukkit.getScheduler().runTask(plugin, () -> {
                FlectoneGrowthMessages.send(core, sender, "admin.migrate-playtime-done", Map.of(
                        "migrated", String.valueOf(finalMigrated),
                        "skipped", String.valueOf(finalSkipped)));

                if (!core.config().networkSyncEnabled()) {
                    FlectoneGrowthMessages.send(core, sender, "admin.migrate-playtime-sync-disabled");
                    return;
                }

                // Any of these players already online at the time the migration ran are
                // still holding their stale, pre-migration cached total - reload every
                // online player's growth state now instead of leaving it stuck until
                // their next relog.
                List<PlatformPlayer> online = Bukkit.getOnlinePlayers().stream()
                        .<PlatformPlayer>map(BukkitPlayerAdapter::new)
                        .toList();
                engine.refreshAfterReload(online);
            });
        });
    }
}
