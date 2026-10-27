package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.file.FileFacade;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.config.ConfigView;

/**
 * If FlectonePulse is installed, ready and has a non-blank top-level {@code server}
 * value in its {@code config.yml} (a plain, free-form string identifying this backend
 * in a network), that value is reused instead of FlectoneGrowth's own {@code server}
 * key so both plugins agree on which server they run on. Read live through
 * FlectonePulse's {@code FileFacade}. Falls back to the supplied value when
 * FlectonePulse is absent, not ready, has no value, or integrations.yml disables it.
 *
 * <p>If FlectonePulse enables after this plugin (it is only a softdepend), it is not
 * ready yet here and the fallback is used; that is expected.</p>
 */
public final class FlectonePulseServerIdResolver {

    private FlectonePulseServerIdResolver() {}

    public static String resolve(JavaPlugin plugin, String fallback, ConfigView integrations) {
        if (!integrations.getBoolean("flectonepulse.enabled", true)
                || !integrations.getBoolean("flectonepulse.server-id", true)
                || !FlectonePulseAccess.isPluginEnabled()) {
            return fallback;
        }

        String serverId;
        try {
            serverId = Read.serverId();
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
        if (serverId == null || serverId.isBlank()) return fallback;

        plugin.getLogger().fine("Using FlectonePulse's server id \"" + serverId + "\" instead of the one in config.yml.");
        return serverId;
    }

    /** Package-private for {@link FlectonePulseHealthCheck}; callers must guard LinkageError. */
    static final class Read {
        private Read() {}

        static String serverId() {
            FileFacade facade = FlectonePulseAccess.tryGet(FileFacade.class);
            return facade != null ? facade.config().server() : null;
        }
    }
}
