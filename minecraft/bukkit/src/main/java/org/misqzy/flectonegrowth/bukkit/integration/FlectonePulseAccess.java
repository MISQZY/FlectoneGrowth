package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.FlectonePulse;
import net.flectone.pulse.FlectonePulseAPI;
import net.flectone.pulse.util.file.FileFacade;
import org.bukkit.Bukkit;

/**
 * Shared entry point into FlectonePulse's actual Java API, used by both
 * {@link FlectonePulseServerIdResolver} and {@link FlectonePulseColorResolver}.
 *
 * <p>{@code net.flectone.pulse:core} is published to Maven Central
 * (verified: {@code repo1.maven.org} resolves {@code core-1.12.0.jar}
 * directly) and, since some version, exposes a real static entry point:
 * {@code FlectonePulseAPI.getInstance()} returns the live {@code FlectonePulse}
 * instance, whose {@code <T> T get(Class<T>)} is a Guice service-locator
 * reaching its internal singletons - {@code FileFacade} in particular holds
 * every parsed config file ({@code config()}, {@code message()}, ...),
 * always reflecting FlectonePulse's current in-memory state (including its
 * own {@code /flectonepulse reload}). Confirmed the exact method signatures
 * used here by decompiling the real jar with {@code javap}, and compiled a
 * standalone test file against it - not guessed from documentation.</p>
 *
 * <p>This is still FlectonePulse's internal surface, not a documented,
 * version-stability-guaranteed public API, so every call in this class is
 * defensively wrapped: {@link RuntimeException} covers Guice
 * ({@code ConfigurationException}/{@code ProvisionException}) if a binding
 * is ever missing, and {@link LinkageError} covers an older FlectonePulse
 * version that predates one of these classes/methods entirely
 * ({@code NoSuchMethodError}/{@code NoClassDefFoundError}). Either way,
 * callers fall back to their own alternative rather than failing.</p>
 *
 * <p>Deliberately silent (no logging) - {@link #tryGetFileFacade()} backs
 * {@code Messages}' per-message {@code <fcolor:N>} lookup, so it can run on
 * every single chat/command message. For a one-time, admin-visible report of
 * exactly which step this fails at (not installed vs. not ready vs. present
 * but threw vs. fcolor module disabled in FlectonePulse's own config), see
 * {@link FlectonePulseColorResolver#logDiagnostics}, run once per
 * {@code onEnable()}/{@code reload()} instead.</p>
 *
 * <p>{@link #tryGet} is the same lookup generalised to any of FlectonePulse's
 * Guice-managed singletons - {@link FlectonePulseMessageDispatcher} uses it
 * for {@code FPlayerService}/{@code MessagePipeline}/{@code MessageDispatcher}.</p>
 */
final class FlectonePulseAccess {

    /** The Bukkit plugin name FlectonePulse registers under - the single source of truth every class in this package checks {@link Bukkit#getPluginManager()} against. */
    static final String PLUGIN_NAME = "FlectonePulse";

    private FlectonePulseAccess() {}

    /**
     * FlectonePulse's live {@link FileFacade}, or {@code null} if unavailable
     * for any reason.
     *
     * <p>Needs its own try/catch around the {@code FileFacade.class} literal
     * itself, not just the one inside {@link #tryGet} - a real live-deploy
     * crash: with FlectonePulse not installed, {@code net.flectone.pulse.*}
     * isn't on the classpath at all, so resolving that class literal throws
     * {@link NoClassDefFoundError} in <em>this</em> method, one frame before
     * {@link #tryGet}'s own try block is ever entered. {@code tryGet}'s catch
     * can't help - by the time it would run, the failure already happened in
     * its caller.</p>
     */
    static FileFacade tryGetFileFacade() {
        try {
            return tryGet(FileFacade.class);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Any of FlectonePulse's Guice-managed singletons, or {@code null} if unavailable for any reason (not installed, not ready, or an incompatible version). */
    static <T> T tryGet(Class<T> type) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) return null;

            FlectonePulse instance = FlectonePulseAPI.getInstance();
            if (instance == null || !instance.isReady()) return null;

            return instance.get(type);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
