package org.misqzy.flectonegrowth.common.config;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Every parsed config resource {@code FlectoneGrowthCore} needs to (re)build
 * its state from, grouped into one value - {@code bootstrap} and {@code
 * reload} both take exactly this set together, and passing five separate
 * positional parameters at every call site (a platform module's own
 * {@code bootstrapCore()}/{@code reload()}) invited the kind of copy-paste
 * drift a named bundle avoids.
 *
 * @param mainConfig         parsed config.yml
 * @param genderConfig       parsed gender.yml
 * @param messagesByLocale   every loaded {@code localizations/messages_<code>.yml}, keyed by locale code -
 *                           not just the configured default, so {@code Messages} can serve a specific
 *                           recipient's own locale (e.g. a player's Minecraft client locale) on demand
 * @param integrationsConfig parsed integrations.yml
 * @param fcolors            looks up FlectonePulse's configured {@code <fcolor:N>} palette on demand (empty if
 *                           unavailable/disabled) - a live lookup, not a value, since the platform module's
 *                           FlectonePulse detection can lag behind {@code onEnable()} by a beat (see {@code Messages})
 */
public record ConfigBundle(
        ConfigView mainConfig,
        ConfigView genderConfig,
        Map<String, ConfigView> messagesByLocale,
        ConfigView integrationsConfig,
        Supplier<Map<Integer, String>> fcolors
) {}
