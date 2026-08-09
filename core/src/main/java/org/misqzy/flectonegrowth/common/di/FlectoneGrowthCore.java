package org.misqzy.flectonegrowth.common.di;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.misqzy.flectonegrowth.common.platform.PlayerLookup;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.config.ConfigBundle;
import org.misqzy.flectonegrowth.common.config.CoreConfig;
import org.misqzy.flectonegrowth.common.config.IntegrationsConfig;
import org.misqzy.flectonegrowth.common.domain.Gender;
import org.misqzy.flectonegrowth.common.domain.GenderRegistry;
import org.misqzy.flectonegrowth.common.lang.Messages;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.platform.Platform;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.storage.Storage;
import org.misqzy.flectonegrowth.common.storage.StorageFactory;

import java.util.logging.Logger;

/**
 * Entry point every platform module boots through. Mirrors the role
 * FlectonePulse's per-platform {@code *Bootstrap} classes play: build the
 * platform-specific Guice module (Platform/Scheduler/NetworkMessenger/
 * PlayerLookup bindings), hand it here together with the loaded config, and
 * get back a ready {@link GrowthEngine}.
 */
public final class FlectoneGrowthCore {

    private final Platform platform;
    @Getter
    @Accessors(fluent = true)
    private Injector injector;
    // volatile: reload() now runs its blocking file/DB I/O off the main
    // thread (see FlectoneGrowthPlugin#reload) and swaps these in from there,
    // while commands/PAPI/etc. keep reading them on the main thread.
    @Getter
    @Accessors(fluent = true)
    private volatile CoreConfig config;
    @Getter
    @Accessors(fluent = true)
    private volatile IntegrationsConfig integrations;
    @Getter
    @Accessors(fluent = true)
    private volatile Storage storage;
    @Getter
    @Accessors(fluent = true)
    private volatile GenderRegistry genderRegistry;
    @Getter
    @Accessors(fluent = true)
    private volatile Messages messages;

    // Guards reload() against overlapping invocations (e.g. an admin double-tapping /fg reload, or one
    // manual + one scripted trigger landing close together) - see that method's own comment.
    private final Object reloadLock = new Object();

    private FlectoneGrowthCore(Platform platform) {
        this.platform = platform;
    }

    /**
     * @param platform       platform-supplied handle (data folder, logger, scheduler, server id)
     * @param platformModule binds {@link Scheduler}, {@link NetworkMessenger} and {@link PlayerLookup}
     * @param resources      every parsed config resource - see {@link ConfigBundle}
     */
    public static FlectoneGrowthCore bootstrap(Platform platform, Module platformModule, ConfigBundle resources) {
        FlectoneGrowthCore core = new FlectoneGrowthCore(platform);
        core.buildAll(platformModule, resources);
        return core;
    }

    private void buildAll(Module platformModule, ConfigBundle resources) {
        this.config = new CoreConfig(resources.mainConfig());
        this.integrations = new IntegrationsConfig(resources.integrationsConfig());
        this.storage = initStorage(platform.logger(), platform.dataFolder(), config);
        this.genderRegistry = new GenderRegistry(resources.genderConfig(), config.maxScale());
        this.messages = new Messages(resources.messagesByLocale(), config.locale(), config.primaryColor(), config.secondaryColor(), resources.fcolors());

        this.injector = Guice.createInjector(
                new CoreModule(config, storage, genderRegistry),
                platformModule,
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(Platform.class).toInstance(platform);
                    }
                }
        );
    }

    /** Falls back to YAML storage if the configured backend fails to initialise (e.g. bad DB credentials). */
    private static Storage initStorage(Logger logger, java.io.File dataFolder, CoreConfig config) {
        Storage primary = StorageFactory.create(logger, dataFolder, config);
        if (primary.initialize()) {
            if (!primary.testConnection()) {
                logger.warning("Storage connection test failed - some features may not work.");
            }
            return primary;
        }

        logger.severe("Primary storage (" + config.storageType() + ") failed to initialise. Falling back to YAML.");
        Storage fallback = StorageFactory.createFallback(logger, dataFolder);
        if (fallback.initialize()) {
            logger.warning("Using YAML storage as a fallback.");
            return fallback;
        }

        throw new IllegalStateException("All storage backends failed to initialise (configured + YAML fallback).");
    }

    /**
     * Rebuilds config/storage/gender registry from freshly re-read files and hot-swaps them into the
     * running engine.
     *
     * <p>Synchronized on {@link #reloadLock} - without this, two overlapping calls (already reachable via
     * {@code FlectoneGrowthPlugin#reload}, which just dispatches each invocation onto its own async task and
     * returns immediately) could both read the same {@code Storage oldStorage = storage} while it's still
     * the old instance, both independently build their own new {@link Storage} via {@link #initStorage}, and
     * both then unconditionally do {@code this.storage = newStorage} - whichever call's write loses the race
     * leaves its own freshly-built connection pool referenced by nobody, since each only closes the *old*
     * instance it captured, never the other call's *new* one. A real, reachable trigger: an admin re-running
     * {@code /fg reload} while testing new DB credentials.</p>
     */
    public void reload(ConfigBundle resources) {
        synchronized (reloadLock) {
            CoreConfig newConfig = new CoreConfig(resources.mainConfig());
            GenderRegistry newGenderRegistry = new GenderRegistry(resources.genderConfig(), newConfig.maxScale());

            Storage oldStorage = storage;
            Storage newStorage = storage;
            // Comparing only the storage *type* would miss an admin editing DB host/port/credentials/
            // database name/table-prefix while keeping the same backend - that used to leave the old
            // connection pool running against the old settings until a full restart, with nothing
            // telling the admin their edit didn't take effect.
            boolean storageChanged = storage.type() != newConfig.storageType() || connectionSettingsChanged(config, newConfig);
            if (storageChanged) {
                newStorage = initStorage(platform.logger(), platform.dataFolder(), newConfig);
            }

            // NetworkMessenger is bound once, at bootstrap (GrowthEngine holds a final reference to
            // whichever one - a real BukkitNetworkMessenger or NetworkMessenger.DISABLED - the platform
            // module built and bound then) - reload has no way to rebind it, so toggling this here would
            // otherwise silently do nothing (no broadcast/receive change) until a full restart, with the
            // reload still reporting success. Not attempting a live rebind here (it would mean either
            // GrowthEngine holding a mutable messenger reference or fully re-registering the plugin-
            // messaging channel mid-runtime, more risk than a rarely-toggled setting justifies) - warning
            // instead so the gap is at least visible.
            if (config.networkSyncEnabled() != newConfig.networkSyncEnabled()) {
                platform.logger().warning("network.sync-enabled changed (" + config.networkSyncEnabled() + " -> "
                        + newConfig.networkSyncEnabled() + ") but this requires a full restart to take effect - "
                        + "the cross-server sync channel is only ever set up once, at plugin enable.");
            }

            this.config = newConfig;
            this.integrations = new IntegrationsConfig(resources.integrationsConfig());
            this.storage = newStorage;
            this.genderRegistry = newGenderRegistry;
            this.messages = new Messages(resources.messagesByLocale(), newConfig.locale(), newConfig.primaryColor(), newConfig.secondaryColor(), resources.fcolors());

            // GrowthEngine keeps its own Storage reference, only updated by
            // applyReload below - closing oldStorage before that call would let
            // any in-flight async GrowthEngine operation (scheduled before this
            // reload started) call a method on an already-closed HikariDataSource.
            // Closing last, after every consumer has switched to newStorage,
            // shrinks that window down to nothing but the swap itself.
            growthEngine().applyReload(newConfig, newStorage, newGenderRegistry);
            if (storageChanged) oldStorage.close();
        }
    }

    /** Whether any DB connection detail {@link #reload} would otherwise silently keep using has changed - see the call site's comment. */
    private static boolean connectionSettingsChanged(CoreConfig oldConfig, CoreConfig newConfig) {
        return !oldConfig.dbHost().equals(newConfig.dbHost())
                || oldConfig.dbPort() != newConfig.dbPort()
                || !oldConfig.dbName().equals(newConfig.dbName())
                || !oldConfig.dbUsername().equals(newConfig.dbUsername())
                || !oldConfig.dbPassword().equals(newConfig.dbPassword())
                || !oldConfig.dbTablePrefix().equals(newConfig.dbTablePrefix())
                || !oldConfig.dbParams().equals(newConfig.dbParams())
                || oldConfig.maxPoolSize() != newConfig.maxPoolSize()
                || oldConfig.minIdle() != newConfig.minIdle()
                || oldConfig.connectionTimeoutMs() != newConfig.connectionTimeoutMs()
                || oldConfig.idleTimeoutMs() != newConfig.idleTimeoutMs()
                || oldConfig.maxLifetimeMs() != newConfig.maxLifetimeMs();
    }

    public void shutdown() {
        if (storage != null) storage.close();
    }

    public GrowthEngine growthEngine() {
        return injector.getInstance(GrowthEngine.class);
    }

    /**
     * {@code gender}'s configured display name in config.yml's default
     * locale (falls back to its key if none is configured) - the
     * composition every caller needs {@link GenderRegistry#resolveDisplayName}
     * for, in one place instead of each re-wiring {@code messages()::rawOrNull}
     * into it. For a caller rendering a message in a specific recipient's own
     * locale (e.g. a command reply - see {@link #genderDisplayName(Gender, String)}),
     * use the locale-taking overload instead, or this value ends up mismatched
     * against the rest of that message's locale. Callers with no single
     * recipient to localize for (the public API, PlaceholderAPI) use this one.
     */
    public String genderDisplayName(Gender gender) {
        return genderRegistry.resolveDisplayName(gender, messages::rawOrNull);
    }

    /** {@code player}'s *current* gender's display name - see {@link #genderDisplayName(Gender)}. */
    public String genderDisplayName(PlatformPlayer player) {
        return genderDisplayName(growthEngine().genderOf(player));
    }

    /** {@code gender}'s configured display name in {@code locale} (or config.yml's default when {@code locale} is {@code null}/not loaded) - see {@link #genderDisplayName(Gender)}. */
    public String genderDisplayName(Gender gender, String locale) {
        return genderRegistry.resolveDisplayName(gender, key -> messages.rawOrNull(locale, key));
    }

    /** {@code player}'s *current* gender's display name in {@code locale} - see {@link #genderDisplayName(Gender, String)}. */
    public String genderDisplayName(PlatformPlayer player, String locale) {
        return genderDisplayName(growthEngine().genderOf(player), locale);
    }
}
