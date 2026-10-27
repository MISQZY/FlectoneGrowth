package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.dispatcher.MessageDispatcher;
import net.flectone.pulse.file.FileFacade;
import net.flectone.pulse.service.FPlayerService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.logging.Logger;

/**
 * One consolidated, admin-visible report of every FlectonePulse integration point -
 * {@code <fcolor:N>} palette ({@link FlectonePulseColorResolver}), server-id reuse
 * ({@link FlectonePulseServerIdResolver}) and message dispatch
 * ({@link FlectonePulseMessageDispatcher}) - logged on enable, on this plugin's reload,
 * and (via {@link FlectonePulseLifecycleListener}) whenever FlectonePulse finishes
 * starting or reloading. Purely diagnostic; changes no behaviour.
 */
public final class FlectonePulseHealthCheck {

    private FlectonePulseHealthCheck() {}

    /** Safe from any thread: off the main thread it re-schedules itself there (Bukkit's plugin manager is main-thread-only). */
    public static void logSummary(JavaPlugin plugin, ConfigView integrations) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> logSummary(plugin, integrations));
            return;
        }
        Logger logger = plugin.getLogger();

        if (!integrations.getBoolean("flectonepulse.enabled", true)) {
            logger.info("[FlectonePulse] All integrations disabled via integrations.yml - working standalone.");
            return;
        }
        if (!FlectonePulseAccess.isPluginEnabled()) {
            logger.info("[FlectonePulse] Not installed/enabled - working standalone.");
            return;
        }

        FlectonePulseColorResolver.logDiagnostics(plugin, integrations);

        Boolean reachable;
        try {
            reachable = Probe.reachable();
        } catch (RuntimeException | LinkageError e) {
            reachable = null;
        }
        if (reachable == null || !reachable) {
            logger.warning("[FlectonePulse] Server-id reuse and message dispatch fall back to local behaviour: "
                    + FlectonePulseAccess.diagnose() + ".");
            return;
        }

        if (!integrations.getBoolean("flectonepulse.server-id", true)) {
            logger.info("[FlectonePulse] server-id reuse disabled via integrations.yml.");
        } else {
            String serverId = null;
            try {
                serverId = FlectonePulseServerIdResolver.Read.serverId();
            } catch (RuntimeException | LinkageError ignored) {
                // reported as inactive below
            }
            logger.info("[FlectonePulse] server-id reuse " + (serverId == null || serverId.isBlank()
                    ? "inactive (FlectonePulse's `server` key is blank) - using FlectoneGrowth's own."
                    : "active (\"" + serverId + "\")."));
        }

        if (!integrations.getBoolean("flectonepulse.message-dispatch", true)) {
            logger.info("[FlectonePulse] message dispatch disabled via integrations.yml - local rendering only.");
        } else {
            boolean ready;
            try {
                ready = Probe.dispatchReady();
            } catch (RuntimeException | LinkageError e) {
                ready = false;
            }
            logger.info("[FlectonePulse] message dispatch " + (ready
                    ? "active - player messages go through FlectonePulse first."
                    : "unavailable right now (FPlayerService/MessageDispatcher not reachable) - local rendering only."));
        }
    }

    private static final class Probe {
        private Probe() {}

        static boolean reachable() {
            return FlectonePulseAccess.tryGet(FileFacade.class) != null;
        }

        static boolean dispatchReady() {
            return FlectonePulseAccess.tryGet(FPlayerService.class) != null
                    && FlectonePulseAccess.tryGet(MessageDispatcher.class) != null;
        }
    }
}
