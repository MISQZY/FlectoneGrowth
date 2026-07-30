package org.misqzy.flectonegrowth.common.config;

import org.misqzy.flectonegrowth.common.storage.StorageType;

/**
 * Immutable snapshot of everything the growth engine needs from config.yml.
 * A fresh instance is built on every reload; nothing here mutates in place,
 * which removes the "half old / half new config" hazard the original
 * {@code PluginConfig} had when individual fields were re-assigned.
 */
public final class CoreConfig {

    private final String version;
    private final double minScale;
    private final double maxScale;
    private final String locale;
    private final String primaryColor;
    private final String secondaryColor;

    private final long growTimeSeconds;
    private final long growTimeMinSeconds;
    private final long growTimeMaxSeconds;
    private final boolean autoGrowth;
    private final double growthUpdateIntervalSeconds;
    private final boolean pauseWhenBoxedIn;

    private final StorageType storageType;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUsername;
    private final String dbPassword;
    private final String dbTablePrefix;
    private final String dbParams;
    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;

    private final boolean networkSyncEnabled;
    private final boolean networkPerServer;
    private final boolean networkIncludeServer;

    private final boolean updateCheckerEnabled;

    public CoreConfig(ConfigView cfg) {
        this.version = cfg.getString("version", "0.0.0");
        this.minScale = cfg.getDouble("scale.min", 0.3);
        this.maxScale = cfg.getDouble("scale.max", 1.0);
        this.locale = cfg.getString("locale", "en");
        this.primaryColor = cfg.getString("colors.primary", "gold");
        this.secondaryColor = cfg.getString("colors.secondary", "yellow");

        this.growTimeSeconds = cfg.getInt("growth.time-minutes", 2880) * 60L;
        this.growTimeMinSeconds = cfg.getInt("growth.time-min-minutes", 0) * 60L;
        this.growTimeMaxSeconds = cfg.getInt("growth.time-max-minutes", 0) * 60L;
        this.autoGrowth = cfg.getBoolean("growth.auto-growth", true);
        this.growthUpdateIntervalSeconds = cfg.getDouble("growth.update-interval-seconds", 5.0);
        this.pauseWhenBoxedIn = cfg.getBoolean("growth.pause-when-boxed-in", true);

        StorageType parsedType;
        try {
            parsedType = StorageType.valueOf(cfg.getString("storage.type", "H2").toUpperCase());
        } catch (IllegalArgumentException e) {
            parsedType = StorageType.H2;
        }
        this.storageType = parsedType;

        this.dbHost = cfg.getString("storage.database.host", "localhost");
        this.dbPort = cfg.getInt("storage.database.port", 3306);
        // Deliberately still "playergrowth", not "flectonegrowth": this is the
        // MySQL/MariaDB database name an existing install's config.yml relies
        // on by omission (no explicit storage.database.name set). Changing
        // the default would point every upgrading shared-DB install at a
        // different, empty database instead of the one it's always used -
        // unlike the table names below (renamed via LegacyTableMigration),
        // there's no safe way to rename a database out from under a JDBC
        // user that may not even hold the privilege to do so.
        this.dbName = cfg.getString("storage.database.name", "playergrowth");
        this.dbUsername = cfg.getString("storage.database.username", "root");
        this.dbPassword = cfg.getString("storage.database.password", "");
        // Lets a network sharing one physical database run several
        // FlectoneGrowth instances (or coexist with a renamed legacy
        // install) side by side without their tables colliding - default
        // matches every table name this project has always used, so an
        // unset key changes nothing for an existing install. No trailing
        // '_' needed here - TableNames appends it automatically.
        this.dbTablePrefix = cfg.getString("storage.database.table-prefix", "flectonegrowth");
        // Raw JDBC URL query string appended after '?' (e.g.
        // "useSSL=true&characterEncoding=utf8") - blank (the default) keeps
        // using this project's own built-in defaults, see MySQLStorage/
        // MariaDbStorage#buildJdbcUrl. Only consulted for MYSQL/MARIADB.
        this.dbParams = cfg.getString("storage.database.params", "");
        this.maxPoolSize = cfg.getInt("storage.database.pool.maximum-pool-size", 10);
        this.minIdle = cfg.getInt("storage.database.pool.minimum-idle", 2);
        this.connectionTimeoutMs = cfg.getLong("storage.database.pool.connection-timeout", 30_000L);
        this.idleTimeoutMs = cfg.getLong("storage.database.pool.idle-timeout", 600_000L);
        this.maxLifetimeMs = cfg.getLong("storage.database.pool.max-lifetime", 1_800_000L);

        this.networkSyncEnabled = cfg.getBoolean("network.sync-enabled", false);
        this.networkPerServer = cfg.getBoolean("network.per-server", false);
        this.networkIncludeServer = cfg.getBoolean("network.include-server", true);

        this.updateCheckerEnabled = cfg.getBoolean("update-checker.enabled", true);
    }

    /** The plugin build that generated this config.yml - kept in sync on every startup by ConfigMigrator, so this is always the currently-running build's version, not a stale snapshot. */
    public String version() { return version; }

    public double minScale() { return minScale; }
    public double maxScale() { return maxScale; }
    public String locale() { return locale; }
    public String primaryColor() { return primaryColor; }
    public String secondaryColor() { return secondaryColor; }

    public boolean isRangeMode() {
        return growTimeMinSeconds > 0 && growTimeMaxSeconds > growTimeMinSeconds;
    }

    public long growTimeSeconds() { return growTimeSeconds; }
    public long growTimeMinSeconds() { return growTimeMinSeconds; }
    public long growTimeMaxSeconds() { return growTimeMaxSeconds; }
    public boolean autoGrowth() { return autoGrowth; }
    public double growthUpdateIntervalSeconds() { return growthUpdateIntervalSeconds; }
    public boolean pauseWhenBoxedIn() { return pauseWhenBoxedIn; }

    public StorageType storageType() { return storageType; }
    public String dbHost() { return dbHost; }
    public int dbPort() { return dbPort; }
    public String dbName() { return dbName; }
    public String dbUsername() { return dbUsername; }
    public String dbPassword() { return dbPassword; }
    public String dbTablePrefix() { return dbTablePrefix; }
    public String dbParams() { return dbParams; }
    public int maxPoolSize() { return maxPoolSize; }
    public int minIdle() { return minIdle; }
    public long connectionTimeoutMs() { return connectionTimeoutMs; }
    public long idleTimeoutMs() { return idleTimeoutMs; }
    public long maxLifetimeMs() { return maxLifetimeMs; }

    public boolean networkSyncEnabled() { return networkSyncEnabled; }

    /** Whether playtime is tracked in a bucket scoped to this running server (keyed by its own {@code server} id) rather than one shared network-wide total. Only consulted when {@link #networkSyncEnabled()} is on. */
    public boolean networkPerServer() { return networkPerServer; }

    /** Whether time spent on this running server counts towards growth playtime at all - false for a hub/lobby server players idle on between real servers. A per-server setting (this server's own choice), not a network-wide list. Only consulted when {@link #networkSyncEnabled()} is on. */
    public boolean networkIncludeServer() { return networkIncludeServer; }

    public boolean updateCheckerEnabled() { return updateCheckerEnabled; }

    // network.server is deliberately not parsed here: the actual identifier
    // (Platform#serverId()) needs to auto-generate and persist a UUID back
    // to config.yml the first time it's blank, which this immutable,
    // rebuilt-on-every-reload snapshot has no business doing. The platform
    // module resolves and owns it once, at bootstrap - see each platform's
    // FlectoneGrowthPlugin#bootstrapCore() and ServerIdProvisioner.
}
