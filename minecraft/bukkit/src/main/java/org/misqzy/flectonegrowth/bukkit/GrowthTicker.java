package org.misqzy.flectonegrowth.bukkit;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;
import org.misqzy.flectonegrowth.common.platform.ScheduledTask;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;

import java.util.List;

/**
 * Owns the repeating growth tick as its own object (rather than folding it
 * into {@link FlectoneGrowthPlugin}) so it can be stopped and restarted on
 * {@code /flectonegrowth reload} without re-running the rest of plugin bootstrap.
 */
@RequiredArgsConstructor
public final class GrowthTicker {

    private final Scheduler scheduler;
    private final GrowthEngine growthEngine;
    private ScheduledTask task;

    public void start() {
        if (task != null || !growthEngine.autoGrowthEnabled()) return;
        long interval = growthEngine.tickIntervalMillis();
        task = scheduler.runSyncTimer(this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void restart() {
        stop();
        start();
    }

    private void tick() {
        List<PlatformPlayer> online = Bukkit.getOnlinePlayers().stream()
                .<PlatformPlayer>map(BukkitPlayerAdapter::new)
                .toList();
        growthEngine.tick(online);
    }
}
