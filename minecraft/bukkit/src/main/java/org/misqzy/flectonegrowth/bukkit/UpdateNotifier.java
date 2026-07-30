package org.misqzy.flectonegrowth.bukkit;

import org.bukkit.entity.Player;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.notify.Notifier;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.update.UpdateChecker;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Platform glue around {@link UpdateChecker}: runs the GitHub check off the
 * main thread once per startup, logs a console warning if a newer version
 * is found, and - via {@link Notifier} - notifies {@code
 * flectonegrowth.update-notify} holders on join afterwards.
 * {@code update-checker.enabled} in {@code config.yml} (see
 * {@code CoreConfig#updateCheckerEnabled()}) gates both; {@link Notifier}
 * re-reads it live on every {@link #notifyIfPending} call, not just once in
 * {@link #checkAsync}, so disabling it via {@code /flectonegrowth reload}
 * silences further join notifications immediately without a restart.
 */
public final class UpdateNotifier extends Notifier {

    private static final String REPO_OWNER = "MISQZY";
    private static final String REPO_NAME = "FlectoneGrowth";
    private static final String PERMISSION = "flectonegrowth.update-notify";

    private final Scheduler scheduler;
    private final Logger logger;

    private volatile String availableVersion;

    public UpdateNotifier(FlectoneGrowthCore core, Scheduler scheduler, Logger logger) {
        super(core);
        this.scheduler = scheduler;
        this.logger = logger;
    }

    @Override
    protected String permission() {
        return PERMISSION;
    }

    @Override
    protected boolean enabled() {
        return core.config().updateCheckerEnabled();
    }

    public void checkAsync() {
        if (!enabled()) return;

        scheduler.runAsync(() ->
                UpdateChecker.latestVersionIfNewer(REPO_OWNER, REPO_NAME, BuildVersion.VERSION, logger)
                        .ifPresent(latest -> {
                            availableVersion = latest;
                            // Not /releases - this project doesn't publish GitHub Releases, only
                            // tags (see UpdateChecker/docs/ARCHITECTURE.md "Update checker" - the
                            // /releases/latest API endpoint was verified live to 404 for this repo).
                            logger.warning("A new FlectoneGrowth version is available: v" + latest
                                    + " (running v" + BuildVersion.VERSION + "). Get it from "
                                    + "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/tags");
                        }));
    }

    public void notifyIfPending(Player player) {
        String latest = availableVersion;
        if (latest == null) return;

        notify(new BukkitPlayerAdapter(player), "admin.update-available", Map.of(
                "latest", latest,
                "current", BuildVersion.VERSION));
    }
}
