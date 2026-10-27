package org.misqzy.flectonegrowth.bukkit;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigBundle;
import org.misqzy.flectonegrowth.common.config.ConfigView;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.bukkit.api.FlectoneGrowthAPI;
import org.misqzy.flectonegrowth.bukkit.api.FlectoneGrowthAPIImpl;
import org.misqzy.flectonegrowth.bukkit.command.CommandRegistry;
import org.misqzy.flectonegrowth.bukkit.config.ConfigMigrator;
import org.misqzy.flectonegrowth.bukkit.config.LegacyDataFolderMigrator;
import org.misqzy.flectonegrowth.bukkit.config.ResourceInstaller;
import org.misqzy.flectonegrowth.bukkit.config.ServerIdProvisioner;
import org.misqzy.flectonegrowth.bukkit.config.YamlFileLoader;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseColorResolver;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseHealthCheck;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseLifecycleListener;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseServerIdResolver;
import org.misqzy.flectonegrowth.bukkit.integration.PlaceholderAPIHook;
import org.misqzy.flectonegrowth.bukkit.listener.PlayerConnectionListener;
import org.misqzy.flectonegrowth.bukkit.network.BukkitNetworkMessenger;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

public final class FlectoneGrowthPlugin extends JavaPlugin {

    @Getter
    @Accessors(fluent = true)
    private FlectoneGrowthCore core;
    private Scheduler scheduler;
    private GrowthTicker ticker;
    private BukkitNetworkMessenger networkMessenger;
    private PlaceholderAPIHook placeholderHook;
    private UpdateNotifier updateNotifier;
    private String serverId;

    /**
     * The {@code integrations.yml} view currently in effect - read by
     * {@link FlectonePulseLifecycleListener}'s permanent listener every time
     * it fires, so it always sees whatever's current instead of whatever was
     * loaded at {@link #onEnable()}. Updated here and in {@link #reload}.
     */
    private volatile ConfigView integrationsConfig;

    @Override
    public void onEnable() {
        new LegacyDataFolderMigrator(this).migrateIfNeeded();
        new ResourceInstaller(this).installDefaults();
        new ConfigMigrator(this).migrateIfNeeded();

        core = bootstrapCore();

        Bukkit.getServicesManager().register(FlectoneGrowthAPI.class, new FlectoneGrowthAPIImpl(core), this, ServicePriority.Normal);

        updateNotifier = new UpdateNotifier(core, scheduler, getLogger());
        updateNotifier.checkAsync();

        Bukkit.getPluginManager().registerEvents(
                new PlayerConnectionListener(this, core.growthEngine(), updateNotifier), this);

        new CommandRegistry(this, core);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && core.integrations().placeholderApiEnabled()) {
            placeholderHook = new PlaceholderAPIHook(core, scheduler);
            placeholderHook.register();
        }

        ticker = new GrowthTicker(scheduler, core.growthEngine());
        ticker.start();

        getLogger().info("Enabled v" + BuildVersion.VERSION + " (storage: " + core.storage().type()
                + ", server: " + serverId + ")");
    }

    @Override
    public void onDisable() {
        FlectonePulseLifecycleListener.shutdown();
        Bukkit.getServicesManager().unregisterAll(this);
        if (ticker != null) ticker.stop();
        if (placeholderHook != null) placeholderHook.unregister();
        if (networkMessenger != null) networkMessenger.unregister();
        if (core != null) core.shutdown();
    }

    /**
     * Re-reads config/gender/messages from disk and hot-swaps them into the
     * running engine, then re-derives every online player's cached growth
     * state from the new config - without that last step, growth-setting
     * changes (e.g. {@code growth.time-minutes}) silently only took effect
     * for players who relog after the reload, not the ones already online.
     *
     * <p>Re-runs {@link ResourceInstaller} first, same as {@link #onEnable()}:
     * if an admin deletes a bundled resource (e.g. the whole
     * {@code localizations/} folder) after startup and then reloads, this
     * recreates it before {@code loadAllMessages} would otherwise come back
     * empty - idempotent, so it never touches a resource that's still
     * present.</p>
     *
     * <p>Runs off the main thread: installing/migrating resources, reading
     * four YAML files and (when the configured storage backend changed)
     * reconnecting via HikariCP and running schema migrations are all
     * blocking I/O, no different from the storage calls {@link GrowthEngine}
     * already funnels through {@link Scheduler#runAsync} - the admin command
     * that triggers this shouldn't freeze the whole server while a database
     * reconnect is in flight. Only the last step (restarting the ticker and
     * refreshing already-online players) touches Bukkit's Player/scheduler
     * API and hops back to the main thread for that. {@code onSuccess} runs
     * on the main thread once everything has settled, receiving the total
     * wall-clock time (in milliseconds) the whole reload took.
     *
     * <p>{@code onFailure} exists because the async body above can throw - a malformed hand-edited YAML
     * file (a syntax error) or a bad DB config surfaces as a {@link RuntimeException} from {@link
     * #loadConfig}/{@code core.reload}. Uncaught, that used to just kill the async task: Bukkit's own
     * async-task wrapper logs a warning to console, but the command sender who triggered this got no
     * success message, no error message, nothing - core state stays safely on the old config, but there
     * was no user-facing signal of that either.</p>
     */
    public void reload(LongConsumer onSuccess, Runnable onFailure) {
        long startedAt = System.currentTimeMillis();
        scheduler.runAsync(() -> {
            try {
                new ResourceInstaller(this).installDefaults();
                new ConfigMigrator(this).migrateIfNeeded();

                ConfigView mainConfig = loadConfig("config.yml");
                ConfigView integrationsConfig = loadConfig("integrations.yml");
                this.integrationsConfig = integrationsConfig;
                FlectonePulseHealthCheck.logSummary(this, integrationsConfig);
                core.reload(new ConfigBundle(mainConfig, loadConfig("gender.yml"), loadAllMessages(), integrationsConfig,
                        () -> FlectonePulseColorResolver.resolveDefaultColors(integrationsConfig)));
            } catch (RuntimeException e) {
                getLogger().severe("Reload failed, keeping the previous configuration: " + org.misqzy.flectonegrowth.common.log.PluginLog.describe(e));
                if (onFailure != null) scheduler.runSync(onFailure);
                return;
            }

            scheduler.runSync(() -> {
                if (ticker != null) ticker.restart();

                List<PlatformPlayer> online = Bukkit.getOnlinePlayers().stream()
                        .<PlatformPlayer>map(BukkitPlayerAdapter::new)
                        .toList();
                core.growthEngine().refreshAfterReload(online);

                if (onSuccess != null) onSuccess.accept(System.currentTimeMillis() - startedAt);
            });
        });
    }

    private FlectoneGrowthCore bootstrapCore() {
        ConfigView mainConfig = loadConfig("config.yml");
        ConfigView integrationsConfig = loadConfig("integrations.yml");
        this.integrationsConfig = integrationsConfig;

        scheduler = new BukkitScheduler(this);
        NetworkMessenger messenger = createNetworkMessenger(mainConfig);
        String serverId = resolveServerId(mainConfig, integrationsConfig);
        this.serverId = serverId;

        BukkitPlatform platform = new BukkitPlatform(this, scheduler, serverId);
        BukkitModule bukkitModule = new BukkitModule(scheduler, messenger, this::findOnline);

        FlectonePulseHealthCheck.logSummary(this, integrationsConfig);
        // Permanent - FlectonePulse drops its own non-permanent listeners on every
        // /flectonepulse reload, so this must only be called once per onEnable(),
        // never repeated from this plugin's own /flectonegrowth reload path (see
        // FlectonePulseLifecycleListener's class javadoc for why a second call
        // would double up event handling instead of just refreshing state).
        FlectonePulseLifecycleListener.registerOnce(this, () -> this.integrationsConfig);

        return FlectoneGrowthCore.bootstrap(platform, bukkitModule,
                new ConfigBundle(mainConfig, loadConfig("gender.yml"), loadAllMessages(), integrationsConfig,
                        () -> FlectonePulseColorResolver.resolveDefaultColors(integrationsConfig)));
    }

    /** A real plugin-messaging-backed {@link NetworkMessenger} (registered now, torn down in {@link #onDisable}) if {@code network.sync-enabled} is on, otherwise the shared no-op {@link NetworkMessenger#DISABLED}. */
    private NetworkMessenger createNetworkMessenger(ConfigView mainConfig) {
        if (!mainConfig.getBoolean("network.sync-enabled", false)) {
            return NetworkMessenger.DISABLED;
        }
        networkMessenger = new BukkitNetworkMessenger(this);
        networkMessenger.register();
        return networkMessenger;
    }

    /** This server's id (see {@link ServerIdProvisioner}/{@link FlectonePulseServerIdResolver}), persisting it back to config.yml if it changed. */
    private String resolveServerId(ConfigView mainConfig, ConfigView integrationsConfig) {
        String configuredServerId = ServerIdProvisioner.resolveOrGenerate(this, mainConfig);
        String serverId = FlectonePulseServerIdResolver.resolve(this, configuredServerId, integrationsConfig);
        ServerIdProvisioner.persistIfChanged(this, configuredServerId, serverId);
        return serverId;
    }

    private PlatformPlayer findOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? new BukkitPlayerAdapter(player) : null;
    }

    private ConfigView loadConfig(String fileName) {
        return YamlFileLoader.load(new File(getDataFolder(), fileName));
    }

    /**
     * Every {@code localizations/messages_<code>.yml} present on disk, keyed
     * by that lowercase code - not just config.yml's configured {@code
     * locale}, so {@code Messages} can serve a specific recipient's own
     * locale (a player's Minecraft client locale, see
     * {@code FlectoneGrowthMessages}) without needing a reload first. {@code
     * en} is loaded first (if present) so it's the map's iteration-order
     * fallback if the configured locale itself turns out to be unloaded -
     * matches this method's own previous single-file fallback behavior.
     */
    private Map<String, ConfigView> loadAllMessages() {
        Map<String, ConfigView> byLocale = new LinkedHashMap<>();
        File dir = new File(getDataFolder(), "localizations");

        File enFile = new File(dir, "messages_en.yml");
        if (enFile.exists()) byLocale.put("en", YamlFileLoader.load(enFile));

        File[] files = dir.listFiles((d, name) -> name.startsWith("messages_") && name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String code = name.substring("messages_".length(), name.length() - ".yml".length()).toLowerCase(Locale.ROOT);
                byLocale.putIfAbsent(code, YamlFileLoader.load(file));
            }
        }
        return byLocale;
    }
}
