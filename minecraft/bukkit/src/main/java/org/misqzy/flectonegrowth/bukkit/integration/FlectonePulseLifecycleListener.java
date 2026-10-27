package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.annotation.Pulse;
import net.flectone.pulse.listener.PulseListener;
import net.flectone.pulse.model.event.Event;
import net.flectone.pulse.model.event.lifecycle.EnableEvent;
import net.flectone.pulse.model.event.lifecycle.ReloadEvent;
import net.flectone.pulse.platform.registry.ListenerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Keeps {@link FlectonePulseHealthCheck}'s report from going stale by hooking
 * FlectonePulse's own lifecycle events: {@code ReloadEvent} (type END) and
 * {@code EnableEvent} (type READY, i.e. FlectonePulse finishing its startup after us).
 * Every other integration is a plain live pull and needs no events.
 *
 * <p>This outer class is a Bukkit {@link Listener} and mentions no FlectonePulse type,
 * so it links fine when FlectonePulse is missing; the actual {@link PulseListener} is the
 * nested {@link Pulses}, only touched inside a {@code LinkageError} guard.</p>
 *
 * <p>Registration happens exactly once per instance ({@link #registered}), always with
 * {@code registerPermanent} (FlectonePulse drops non-permanent listeners on each of its
 * reloads). Because FlectonePulse may enable after this plugin, or be enabled but not
 * ready yet, {@link #registerOnce} tries immediately, then again on Bukkit's
 * {@code PluginEnableEvent} for FlectonePulse, and finally polls briefly (once a second,
 * up to a minute) while FlectonePulse is present but not ready. FlectonePulse has no
 * unregister-one API, so the registered listener goes inert via {@code plugin.isEnabled()}
 * if this plugin is disabled.</p>
 *
 * <p>Handlers may run on any thread; they only log and read FlectonePulse's state.</p>
 */
public final class FlectonePulseLifecycleListener implements Listener {

    private static final long POLL_PERIOD_TICKS = 20L;
    private static final int MAX_POLLS = 60;

    /** Set from this plugin's onDisable: FlectonePulse has no unregister-one API, so already-registered handlers just go inert. */
    private static volatile boolean disabled;

    private final JavaPlugin plugin;
    private final Supplier<ConfigView> integrationsConfig;
    private final AtomicBoolean registered = new AtomicBoolean();
    private volatile BukkitTask pollTask;
    private int polls;

    private FlectonePulseLifecycleListener(JavaPlugin plugin, Supplier<ConfigView> integrationsConfig) {
        this.plugin = plugin;
        this.integrationsConfig = integrationsConfig;
    }

    /**
     * Call exactly once from {@code onEnable()}, never from this plugin's own reload
     * path. {@code integrationsConfig} is read lazily on every event so it reflects the
     * current integrations.yml view.
     */
    public static void registerOnce(JavaPlugin plugin, Supplier<ConfigView> integrationsConfig) {
        disabled = false;
        FlectonePulseLifecycleListener listener = new FlectonePulseLifecycleListener(plugin, integrationsConfig);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        if (!listener.tryRegister(false) && FlectonePulseAccess.isPluginEnabled()) {
            listener.startPolling();
        }
    }

    /** Call from {@code onDisable()}: makes the permanently registered FlectonePulse handlers inert. */
    public static void shutdown() {
        disabled = true;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (!FlectonePulseAccess.PLUGIN_NAME.equals(event.getPlugin().getName())) return;
        // FlectonePulse restarting recreates its registry, so our registration is gone: allow re-registering.
        registered.set(false);
        cancelPolling();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!FlectonePulseAccess.PLUGIN_NAME.equals(event.getPlugin().getName())) return;
        if (!tryRegister(true)) startPolling();
    }

    /** @return {@code true} once registered (now or earlier). {@code reportOnSuccess}: re-run the health check, since the startup was missed. */
    private boolean tryRegister(boolean reportOnSuccess) {
        if (registered.get()) return true;
        if (!plugin.isEnabled() || !FlectonePulseAccess.isPluginEnabled()) return false;

        // Claim first so two triggers (enable event + poll) can't both register; roll back on failure.
        if (!registered.compareAndSet(false, true)) return true;
        try {
            if (!Pulses.register(this)) {
                registered.set(false);
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            // Hard failure, not "not ready yet": stay claimed so it isn't retried, until FlectonePulse restarts.
            plugin.getLogger().warning("Could not hook into FlectonePulse reload events ("
                    + org.misqzy.flectonegrowth.common.log.PluginLog.describe(e)
                    + "); the integration status won't refresh after its reloads.");
            return true;
        }

        cancelPolling();
        if (reportOnSuccess) logSummaryLater();
        return true;
    }

    private void startPolling() {
        if (registered.get() || pollTask != null || !plugin.isEnabled()) return;
        polls = 0;
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.isEnabled() || tryRegister(true)) {
                cancelPolling();
            } else if (++polls >= MAX_POLLS) {
                cancelPolling();
                plugin.getLogger().warning("FlectonePulse did not become ready within " + MAX_POLLS
                        + "s; its reloads won't refresh the integration status.");
            }
        }, POLL_PERIOD_TICKS, POLL_PERIOD_TICKS);
    }

    private void cancelPolling() {
        BukkitTask task = pollTask;
        pollTask = null;
        if (task != null) task.cancel();
    }

    /** One tick later, so FlectonePulse has finished flipping its ready flag after the event that triggered this. */
    private void logSummaryLater() {
        if (disabled || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> FlectonePulseHealthCheck.logSummary(plugin, integrationsConfig.get()));
    }

    /** Takes only JDK types: Bukkit reflects over every declared method of this class, so no FlectonePulse type may appear in any signature here. */
    private void onFlectonePulseReloaded(boolean successful, String failureMessage) {
        if (disabled || !plugin.isEnabled()) return;
        Logger logger = plugin.getLogger();
        if (successful) {
            logger.fine("FlectonePulse reloaded, re-checking integration status.");
        } else {
            logger.warning("FlectonePulse reload failed, its previous state stays in effect: "
                    + (failureMessage != null ? failureMessage : "unknown error"));
        }
        // logSummary itself hops to the main thread if called from elsewhere.
        FlectonePulseHealthCheck.logSummary(plugin, integrationsConfig.get());
    }

    /** The actual FlectonePulse listener. Public so FlectonePulse can invoke its annotated methods reflectively. */
    public static final class Pulses implements PulseListener {
        // Weak: FlectonePulse holds this object permanently, and must not keep the listener (and our plugin) alive.
        private final WeakReference<FlectonePulseLifecycleListener> owner;

        private Pulses(FlectonePulseLifecycleListener owner) {
            this.owner = new WeakReference<>(owner);
        }

        /** @return {@code false} if FlectonePulse isn't ready to take listeners yet. */
        static boolean register(FlectonePulseLifecycleListener owner) {
            ListenerRegistry registry = FlectonePulseAccess.tryGet(ListenerRegistry.class);
            if (registry == null) return false;
            registry.registerPermanent(new Pulses(owner));
            return true;
        }

        @Pulse(priority = Event.Priority.MONITOR)
        public Event onReload(ReloadEvent event) {
            FlectonePulseLifecycleListener o = disabled ? null : owner.get();
            if (o != null && event.type() == ReloadEvent.Type.END) {
                o.onFlectonePulseReloaded(event.isSuccessful(),
                        event.reloadException() != null ? event.reloadException().getMessage() : null);
            }
            return event;
        }

        @Pulse(priority = Event.Priority.MONITOR)
        public Event onEnable(EnableEvent event) {
            FlectonePulseLifecycleListener o = disabled ? null : owner.get();
            if (o != null && event.type() == EnableEvent.Type.READY) o.logSummaryLater();
            return event;
        }
    }
}
