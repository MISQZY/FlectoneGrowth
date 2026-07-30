package org.misqzy.flectonegrowth.common.config;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Immutable snapshot of {@code integrations.yml} - lets an admin disable
 * individual FlectoneGrowth integrations, or a specific part of one, without
 * needing to remove the other plugin. A fresh instance is built on every
 * reload, same as {@link CoreConfig}.
 *
 * <p>Each FlectonePulse submodule getter is also gated on the module's own
 * {@code enabled} flag - a submodule can't be on if the whole integration is
 * off, so callers only need to check one method rather than two.</p>
 */
@Getter
@Accessors(fluent = true)
public final class IntegrationsConfig {

    private final boolean placeholderApiEnabled;
    private final boolean flectonePulseEnabled;
    private final boolean flectonePulseColors;
    private final boolean flectonePulseServerId;
    private final boolean flectonePulseMessageDispatch;

    public IntegrationsConfig(ConfigView cfg) {
        this.placeholderApiEnabled = cfg.getBoolean("placeholderapi.enabled", true);
        this.flectonePulseEnabled = cfg.getBoolean("flectonepulse.enabled", true);
        this.flectonePulseColors = flectonePulseEnabled && cfg.getBoolean("flectonepulse.colors", true);
        this.flectonePulseServerId = flectonePulseEnabled && cfg.getBoolean("flectonepulse.server-id", true);
        this.flectonePulseMessageDispatch = flectonePulseEnabled && cfg.getBoolean("flectonepulse.message-dispatch", true);
    }
}
