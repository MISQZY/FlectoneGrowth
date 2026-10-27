package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.config.Message;
import net.flectone.pulse.file.FileFacade;
import org.misqzy.flectonegrowth.common.config.ConfigView;
import org.misqzy.flectonegrowth.common.log.PluginLog;

import java.util.Map;

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

    /** Short status fragment for the one-line report ({@link FlectonePulseHealthCheck}); {@code problem} marks a state worth a warning. */
    record Summary(String text, boolean problem) {}

    static Summary summarize(ConfigView integrations) {
        if (!enabled(integrations)) return new Summary("colors off", false);
        try {
            Palette.Status status = Palette.status();
            return switch (status.kind()) {
                case UNREACHABLE -> new Summary("colors unavailable", true);
                case DISABLED -> new Summary("colors unavailable (fcolor is off in FlectonePulse message.yml)", true);
                case ACTIVE -> new Summary("colors " + status.size(), false);
            };
        } catch (RuntimeException | LinkageError e) {
            return new Summary("colors unavailable (" + PluginLog.describe(e) + ")", true);
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
