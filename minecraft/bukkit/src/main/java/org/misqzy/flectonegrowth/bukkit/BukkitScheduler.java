package org.misqzy.flectonegrowth.bukkit;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.misqzy.flectonegrowth.common.platform.ScheduledTask;
import org.misqzy.flectonegrowth.common.platform.Scheduler;

/**
 * {@code org.bukkit.scheduler.BukkitScheduler}-backed {@link Scheduler} -
 * plain Bukkit API, so it works unmodified on Spigot/CraftBukkit, Paper,
 * and forks like Purpur. Folia (a Paper fork) is not supported: it doesn't
 * implement the classic {@code BukkitScheduler} at all for entity-affecting
 * work, which would need per-entity region-scheduler calls instead - see
 * {@code docs/ARCHITECTURE.md}'s "Known, accepted limitations".
 */
@RequiredArgsConstructor
public final class BukkitScheduler implements Scheduler {

    private final JavaPlugin plugin;

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public ScheduledTask runSyncTimer(Runnable task, long delayMillis, long periodMillis) {
        long delayTicks = Math.max(0L, delayMillis / 50L);
        long periodTicks = Math.max(1L, periodMillis / 50L);
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return bukkitTask::cancel;
    }
}
