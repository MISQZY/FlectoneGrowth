package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.util.file.FileFacade;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;

/**
 * If FlectonePulse is installed and enabled, its own config carries a
 * network-wide {@code server} identifier (added in FlectonePulse 1.10.0,
 * used there for cross-server ban/mute/warn filtering - see
 * {@code net.flectone.pulse.config.Config#server}) that serves exactly the
 * same purpose as FlectoneGrowth's own top-level {@code server} key. Reusing it
 * instead of a separately-configured FlectoneGrowth value keeps the two
 * plugins from silently disagreeing about which backend server they're each
 * running on when both are present in the same network.
 *
 * <p>Despite FlectonePulse's changelog calling this a "server UUID", the
 * underlying config field is a plain {@code String} (not a parsed
 * {@link java.util.UUID}) - confirmed by reading FlectonePulse's own
 * {@code Config.java} record, which declares {@code String server}. It's
 * whatever free-form value the admin put in FlectonePulse's config, so it's
 * treated as an opaque string here too.</p>
 *
 * <p>Read through {@link FlectonePulseAccess} (FlectonePulse's own live,
 * parsed config, via its Java API) rather than reading its config.yml off
 * disk - the API path can't drift out of sync with an in-memory
 * {@code /flectonepulse reload}, and doesn't need this class to re-implement
 * FlectonePulse's own YAML parsing. No disk-read fallback: an install
 * running a FlectonePulse version old enough to predate this API method
 * simply falls back to {@code fallback}, same as FlectonePulse not being
 * installed at all.</p>
 */
public final class FlectonePulseServerIdResolver {

    private FlectonePulseServerIdResolver() {}

    /**
     * Returns FlectonePulse's configured server id, or {@code fallback} if
     * FlectonePulse isn't present/ready/configured, or if {@code integrations}
     * has {@code flectonepulse.enabled}/{@code flectonepulse.server-id} set
     * to {@code false}.
     */
    public static String resolve(JavaPlugin plugin, String fallback, ConfigView integrations) {
        if (!integrations.getBoolean("flectonepulse.enabled", true)
                || !integrations.getBoolean("flectonepulse.server-id", true)) {
            return fallback;
        }

        FileFacade fileFacade = FlectonePulseAccess.tryGetFileFacade();
        String serverId = fileFacade != null ? fileFacade.config().server() : null;
        if (serverId == null || serverId.isBlank()) return fallback;

        plugin.getLogger().info("Using FlectonePulse's configured server id (\"" + serverId
                + "\") instead of the server key from FlectoneGrowth's own config.yml.");
        return serverId;
    }
}
