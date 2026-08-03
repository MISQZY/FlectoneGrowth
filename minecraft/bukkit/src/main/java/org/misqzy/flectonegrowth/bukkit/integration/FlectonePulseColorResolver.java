package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.config.Message;
import net.flectone.pulse.util.file.FileFacade;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;
import org.misqzy.flectonegrowth.common.lang.Messages;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Resolves FlectonePulse's configured {@code <fcolor:N>} default color
 * palette ({@code message.yml}'s {@code format.fcolor.default_colors}, a
 * {@code Map<Integer, String>} of MiniMessage color/gradient fragments,
 * e.g. {@code 1 -> "<gradient:#A6D8FF:#8CC8FF>"}, {@code 3 -> "#A9A9A9"}) so
 * FlectoneGrowth's own messages can use the same {@code <fcolor:N>} tag and
 * stay visually consistent with FlectonePulse's palette.
 *
 * <p>Only the palette <em>defaults</em> are read - not FlectonePulse's
 * per-player "SEE"/"OUT" color overrides, which live in its own database
 * and require a live chat {@code MessageContext} (sender/receiver/permission
 * checks) to resolve; that's FlectonePulse's own internal machinery, not
 * something reachable from outside it. See
 * {@link Messages} for how
 * {@code <fcolor:N>} degrades when FlectonePulse isn't installed, not ready,
 * or a requested index isn't configured: the tag resolves to a no-op
 * (no color change) rather than crashing or leaving literal text behind.</p>
 */
public final class FlectonePulseColorResolver {

    private FlectonePulseColorResolver() {}

    /**
     * FlectonePulse's configured default fcolor palette, or an empty map if
     * unavailable/disabled - including disabled via {@code integrations.yml}'s
     * {@code flectonepulse.enabled}/{@code flectonepulse.colors}.
     */
    public static Map<Integer, String> resolveDefaultColors(ConfigView integrations) {
        if (!integrations.getBoolean("flectonepulse.enabled", true)
                || !integrations.getBoolean("flectonepulse.colors", true)) {
            return Map.of();
        }

        FileFacade fileFacade = FlectonePulseAccess.tryGetFileFacade();
        if (fileFacade == null) return Map.of();

        Message.Format.FColor fcolor = fileFacade.message().format().fcolor();
        if (fcolor == null || !Boolean.TRUE.equals(fcolor.enable())) return Map.of();

        Map<Integer, String> colors = fcolor.defaultColors();
        return colors != null ? colors : Map.of();
    }

    /**
     * Logs a one-time, admin-visible report of exactly what
     * {@link #resolveDefaultColors()} would see right now - which specific
     * step it stops at if the palette comes back empty. Intended to be
     * called once per {@code onEnable()}/{@code reload()}, not from the
     * per-message lookup {@link #resolveDefaultColors()} backs (that one
     * stays silent deliberately - see {@link FlectonePulseAccess}).
     *
     * <p>Added after a live-deploy report of {@code <fcolor:N>} still not
     * resolving with FlectonePulse confirmed installed and enabled - without
     * this, there was no way to tell from the outside whether the miss was
     * "FlectonePulse's API not ready yet", "an exception mid-lookup" (both
     * previously swallowed silently), or "the fcolor module itself is
     * disabled in FlectonePulse's own message.yml" (a FlectonePulse-side
     * config choice, not something a FlectoneGrowth bug could fix).</p>
     */
    public static void logDiagnostics(JavaPlugin plugin, ConfigView integrations) {
        Logger logger = plugin.getLogger();

        if (!integrations.getBoolean("flectonepulse.enabled", true)
                || !integrations.getBoolean("flectonepulse.colors", true)) {
            logger.info("[FlectonePulse] fcolor integration disabled via integrations.yml - <fcolor:N> tags will be a no-op.");
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled(FlectonePulseAccess.PLUGIN_NAME)) {
            logger.info("[FlectonePulse] Not installed/enabled - <fcolor:N> tags will be a no-op.");
            return;
        }

        FileFacade fileFacade = FlectonePulseAccess.tryGetFileFacade();
        if (fileFacade == null) {
            logger.warning("[FlectonePulse] Detected as enabled, but its API wasn't reachable just now "
                    + "(either still starting up, or an incompatible version) - <fcolor:N> tags will be a "
                    + "no-op until the next /flectonegrowth reload succeeds in reaching it.");
            return;
        }

        Message.Format.FColor fcolor = fileFacade.message().format().fcolor();
        if (fcolor == null || !Boolean.TRUE.equals(fcolor.enable())) {
            logger.warning("[FlectonePulse] API reachable, but its fcolor module is disabled "
                    + "(message.yml's format.fcolor.enable is not true in FlectonePulse's own config) - "
                    + "<fcolor:N> tags will be a no-op until that's turned on there.");
            return;
        }

        Map<Integer, String> colors = fcolor.defaultColors();
        logger.info("[FlectonePulse] fcolor integration active - " + (colors != null ? colors.size() : 0)
                + " default color(s) configured");
    }
}
