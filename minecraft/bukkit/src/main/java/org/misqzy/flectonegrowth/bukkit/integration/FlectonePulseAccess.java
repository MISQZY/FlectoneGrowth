package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.FlectonePulse;
import net.flectone.pulse.FlectonePulseAPI;
import org.bukkit.Bukkit;

/**
 * Shared, never-throwing entry point into FlectonePulse's stable public API
 * ({@code net.flectone.pulse:api}), used by every other class in this package.
 *
 * <p>FlectonePulse is only a {@code softdepend}, so its classes may be absent at
 * runtime. The rule throughout this package: anything that mentions a
 * {@code net.flectone.pulse.*} type lives in a small private nested class; the
 * public entry points keep FlectonePulse types out of their signatures and call
 * into those nested classes inside {@code try/catch (RuntimeException | LinkageError)}.
 * A missing FlectonePulse therefore surfaces as a caught {@link NoClassDefFoundError}
 * at that single call rather than as a failure to link the caller itself. Callers
 * also keep their own {@code Class<?>} literals for FlectonePulse types inside such
 * nested classes, never in a frame outside the guard.</p>
 *
 * <p>Nothing here ever hands FlectonePulse an object of a relocated type: this
 * plugin's shaded {@code net.kyori} is a different class set from the plain
 * {@code net.kyori} FlectonePulse uses.</p>
 */
final class FlectonePulseAccess {

    /** The Bukkit plugin name FlectonePulse registers under. */
    static final String PLUGIN_NAME = "FlectonePulse";

    private FlectonePulseAccess() {}

    /** {@code true} if the FlectonePulse plugin is currently enabled. Touches no FlectonePulse class. */
    static boolean isPluginEnabled() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One of FlectonePulse's injected services, or {@code null} if unavailable for any reason
     * (not installed, shutting down, not ready yet, binding missing, incompatible version).
     * Silent and never throws, so it is safe on the per-message hot path.
     *
     * <p>Callers must evaluate the {@code type} class literal inside their own guarded nested
     * class - see the class javadoc.</p>
     */
    static <T> T tryGet(Class<T> type) {
        if (!isPluginEnabled()) return null;
        try {
            return Live.get(type);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** One-shot, human-readable reason {@link #tryGet} would currently return {@code null}. For the health check only. */
    static String diagnose() {
        if (!isPluginEnabled()) return "not installed/enabled";
        try {
            return Live.diagnose();
        } catch (RuntimeException | LinkageError e) {
            return "API unreachable (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    /** Holds every direct FlectonePulse reference of this class; loaded only after the plugin-enabled check. */
    private static final class Live {
        private Live() {}

        static <T> T get(Class<T> type) {
            if (FlectonePulseAPI.isDisabling()) return null;
            FlectonePulse instance = FlectonePulseAPI.getInstance();
            if (instance == null || !instance.isReady()) return null;
            return instance.get(type);
        }

        static String diagnose() {
            if (FlectonePulseAPI.isDisabling()) return "FlectonePulse is shutting down";
            FlectonePulse instance = FlectonePulseAPI.getInstance();
            if (instance == null) return "FlectonePulseAPI.getInstance() is null (still starting?)";
            if (!instance.isReady()) return "FlectonePulse is not ready yet";
            return "ready";
        }
    }
}
