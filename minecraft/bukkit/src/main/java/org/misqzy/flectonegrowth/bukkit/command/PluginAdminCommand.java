package org.misqzy.flectonegrowth.bukkit.command;

import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthPlugin;
import org.misqzy.flectonegrowth.common.lang.TimeFormatter;

import java.util.Map;

/** Administrative commands - currently just {@code /flectonegrowth reload}. */
@RequiredArgsConstructor
public final class PluginAdminCommand {

    private final FlectoneGrowthPlugin plugin;

    @Command("flectonegrowth|fg reload")
    @CommandDescription("Reload the FlectoneGrowth configuration.")
    @Permission("flectonegrowth.admin.reload")
    public void reload(CommandSender sender) {
        String locale = FlectoneGrowthMessages.resolveLocale(plugin.core(), sender);
        plugin.reload(
                time -> FlectoneGrowthMessages.send(plugin.core(), sender, "admin.reloaded",
                        Map.of("time", TimeFormatter.formatMillis(time, plugin.core().messages(), locale))),
                () -> FlectoneGrowthMessages.send(plugin.core(), sender, "command.error"));
    }
}
