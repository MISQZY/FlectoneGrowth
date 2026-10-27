package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.config.Message;
import net.flectone.pulse.file.FileFacade;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Resolves FlectonePulse's configured {@code <fcolor:N>} default palette
 * ({@code message.yml}'s {@code format.fcolor.default_colors}, e.g.
 * {@code 1 -> "<gradient:#A6D8FF:#8CC8FF>"}) so FlectoneGrowth's own locally
 * rendered messages stay visually consistent with FlectonePulse's.
 *
 * <p>The palette is read live from FlectonePulse's {@code FileFacade} on every call,
 * so it always reflects FlectonePulse's current in-memory config, including after its
 * own reload. Only the palette defaults are read, not per-player colour overrides
 * (those need a live chat context inside FlectonePulse). When FlectonePulse is absent,
 * not ready or the fcolor module is off, the result is empty and {@code <fcolor:N>}
 * degrades to a no-op in {@code Messages}.</p>
 */
public final class FlectonePulseColorResolver {

    private FlectonePulseColorResolver() {}

    /** FlectonePulse's default fcolor palette, or an empty map if unavailable or disabled (including via integrations.yml). */
    public static Map<Integer, String> resolveDefaultColors(ConfigView integrations) {
        if (!enabled(integrations) || !FlectonePulseAccess.isPluginEnabled()) return Map.of();
        try {
            return Palette.read();
        } catch (RuntimeException | LinkageError e) {
            return Map.of();
        }
    }

    /**
     * Logs a one-time, admin-visible report of what {@link #resolveDefaultColors} would see
     * right now and, if the palette is unavailable, why. Call once per enable/reload; the
     * per-message lookup above stays silent on purpose.
     */
    public static void logDiagnostics(JavaPlugin plugin, ConfigView integrations) {
        Logger logger = plugin.getLogger();

        if (!enabled(integrations)) {
            logger.info("[FlectonePulse] fcolor integration disabled via integrations.yml - <fcolor:N> tags will be a no-op.");
            return;
        }
        if (!FlectonePulseAccess.isPluginEnabled()) {
            logger.info("[FlectonePulse] Not installed/enabled - <fcolor:N> tags will be a no-op.");
            return;
        }

        try {
            Palette.Status status = Palette.status();
            switch (status.kind()) {
                case UNREACHABLE -> logger.warning("[FlectonePulse] Enabled, but its API isn't reachable right now ("
                        + FlectonePulseAccess.diagnose() + ") - <fcolor:N> tags are a no-op until it is.");
                case DISABLED -> logger.warning("[FlectonePulse] API reachable, but its fcolor module is disabled "
                        + "(message.yml format.fcolor.enable) - <fcolor:N> tags are a no-op until enabled there.");
                case ACTIVE -> logger.info("[FlectonePulse] fcolor integration active - " + status.size() + " default color(s) configured.");
            }
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[FlectonePulse] Could not read the fcolor palette (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ") - <fcolor:N> tags are a no-op.");
        }
    }

    private static boolean enabled(ConfigView integrations) {
        return integrations.getBoolean("flectonepulse.enabled", true)
                && integrations.getBoolean("flectonepulse.colors", true);
    }

    /** All FlectonePulse-typed code; only loaded after the plugin-enabled check, inside the callers' LinkageError guards. */
    private static final class Palette {
        enum Kind { UNREACHABLE, DISABLED, ACTIVE }

        record Status(Kind kind, int size) {}

        private Palette() {}

        static Map<Integer, String> read() {
            FileFacade facade = FlectonePulseAccess.tryGet(FileFacade.class);
            if (facade == null) return Map.of();
            Message.Format.FColor fcolor = facade.message().format().fcolor();
            if (fcolor == null || !Boolean.TRUE.equals(fcolor.enable())) return Map.of();
            Map<Integer, String> colors = fcolor.defaultColors();
            return colors != null ? colors : Map.of();
        }

        static Status status() {
            FileFacade facade = FlectonePulseAccess.tryGet(FileFacade.class);
            if (facade == null) return new Status(Kind.UNREACHABLE, 0);
            Message.Format.FColor fcolor = facade.message().format().fcolor();
            if (fcolor == null || !Boolean.TRUE.equals(fcolor.enable())) return new Status(Kind.DISABLED, 0);
            Map<Integer, String> colors = fcolor.defaultColors();
            return new Status(Kind.ACTIVE, colors != null ? colors.size() : 0);
        }
    }
}
