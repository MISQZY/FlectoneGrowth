package org.misqzy.flectonegrowth.bukkit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.platform.Platform;
import org.misqzy.flectonegrowth.common.platform.Scheduler;

import java.io.File;
import java.util.logging.Logger;

@RequiredArgsConstructor
@Accessors(fluent = true)
public final class BukkitPlatform implements Platform {

    private final JavaPlugin plugin;
    @Getter
    private final Scheduler scheduler;
    @Getter
    private final String serverId;

    @Override
    public String name() {
        return "Bukkit";
    }

    @Override
    public File dataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }
}
