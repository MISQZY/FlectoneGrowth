package org.misqzy.flectonegrowth.bukkit.config;

import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Copies the bundled default resource files out of the plugin jar on first
 * run. Idempotent - resources already present on disk are left untouched
 * rather than calling {@link JavaPlugin#saveResource} unconditionally,
 * which would otherwise require callers to remember the "replace" flag.
 */
@RequiredArgsConstructor
public final class ResourceInstaller {

    private static final String[] RESOURCES = {
            "config.yml",
            "gender.yml",
            "integrations.yml",
            "localizations/messages_en.yml",
            "localizations/messages_ru.yml"
    };

    private final JavaPlugin plugin;

    public void installDefaults() {
        for (String resource : RESOURCES) {
            File target = new File(plugin.getDataFolder(), resource);
            if (target.exists()) continue;
            plugin.saveResource(resource, false);
        }
    }
}
