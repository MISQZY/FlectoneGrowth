package org.misqzy.flectonegrowth.common.notify;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;

import java.util.Map;

/**
 * Base for any feature that sends an in-game notification to a permitted
 * {@link PlatformPlayer}. Centralizes the "should this player receive it
 * right now" gate - a permission check plus a live-read enabled flag - so
 * each concrete notifier only supplies its permission node, its
 * {@link #enabled()} check, and the actual message key/placeholders,
 * instead of re-implementing the same two guards.
 *
 * <p>Built only on {@link PlatformPlayer}/{@link FlectoneGrowthCore}, no
 * Bukkit dependency, so it lives in {@code core} rather than a platform
 * module and any platform that implements {@link PlatformPlayer} can reuse
 * it. Sends via {@code target.sendMessage(core.messages().get(...))}
 * directly rather than a platform module's own FlectonePulse-dispatch-then-
 * fallback entry point (e.g. {@code minecraft/bukkit}'s
 * {@code FlectoneGrowthMessages}) - deliberate: a plugin-update notice isn't
 * a "real" chat message and has no business being relayed through
 * FlectonePulse's own pipeline (e.g. to a Discord bridge) just because the
 * recipient happens to be online.</p>
 *
 * <p>{@link #enabled()} is read live on every {@link #notify} call, not
 * cached at construction: see {@code UpdateNotifier} for why that matters -
 * a config toggle flipped via {@code /flectonegrowth reload} takes effect on
 * the very next notification, no restart needed.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class Notifier {

    protected final FlectoneGrowthCore core;

    /** Permission node a player must hold to receive this notifier's messages. */
    protected abstract String permission();

    /** Whether this notifier is currently allowed to send anything at all. */
    protected abstract boolean enabled();

    protected final void notify(PlatformPlayer target, String key, Map<String, Object> placeholders) {
        if (!enabled()) return;
        if (!target.hasPermission(permission())) return;

        target.sendMessage(core.messages().get(key, placeholders));
    }
}
