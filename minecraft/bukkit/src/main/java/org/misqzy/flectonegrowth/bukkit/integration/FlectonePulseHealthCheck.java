package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.dispatcher.MessageDispatcher;
import net.flectone.pulse.file.FileFacade;
import net.flectone.pulse.service.FPlayerService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.logging.Level;
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

    /** Last line logged, so identical re-checks (reloads, FlectonePulse reloads) stay quiet. */
    private static volatile String lastSummary;

    /**
     * Logs at most one concise line: nothing at INFO when FlectonePulse is absent or switched off, otherwise
     * {@code FlectonePulse: colors 5, server-id "main", dispatch on} (WARNING if something is not working).
     * Safe from any thread: off the main thread it re-schedules itself there (Bukkit's plugin manager is main-thread-only).
     */
    public static void logSummary(JavaPlugin plugin, ConfigView integrations) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> logSummary(plugin, integrations));
            return;
        }
        Logger logger = plugin.getLogger();

        if (!integrations.getBoolean("flectonepulse.enabled", true)) {
            logger.fine("FlectonePulse integration is disabled in integrations.yml.");
            return;
        }
        if (!FlectonePulseAccess.isPluginEnabled()) {
            logger.fine("FlectonePulse not found, running standalone.");
            return;
        }

        Boolean reachable;
        try {
            reachable = Probe.reachable();
        } catch (RuntimeException | LinkageError e) {
            reachable = null;
        }
        if (reachable == null || !reachable) {
            emit(logger, true, "FlectonePulse found but its API is unreachable (" + FlectonePulseAccess.diagnose()
                    + "), using local fallbacks");
            return;
        }

        boolean problem = false;
        StringBuilder line = new StringBuilder("FlectonePulse: ");

        FlectonePulseColorResolver.Summary colors = FlectonePulseColorResolver.summarize(integrations);
        line.append(colors.text());
        problem |= colors.problem();

        line.append(", server-id ");
        if (!integrations.getBoolean("flectonepulse.server-id", true)) {
            line.append("off");
        } else {
            String serverId = null;
            try {
                serverId = FlectonePulseServerIdResolver.Read.serverId();
            } catch (RuntimeException | LinkageError ignored) {
                // reported as "own" below
            }
            line.append(serverId == null || serverId.isBlank() ? "own" : "\"" + serverId + "\"");
        }

        line.append(", dispatch ");
        if (!integrations.getBoolean("flectonepulse.message-dispatch", true)) {
            line.append("off");
        } else {
            boolean ready;
            try {
                ready = Probe.dispatchReady();
            } catch (RuntimeException | LinkageError e) {
                ready = false;
            }
            line.append(ready ? "on" : "unavailable");
            problem |= !ready;
        }
        emit(logger, problem, line.toString());
    }

    private static void emit(Logger logger, boolean problem, String line) {
        String previous = lastSummary;
        lastSummary = line;
        Level level = line.equals(previous) ? Level.FINE : (problem ? Level.WARNING : Level.INFO);
        logger.log(level, line);
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
