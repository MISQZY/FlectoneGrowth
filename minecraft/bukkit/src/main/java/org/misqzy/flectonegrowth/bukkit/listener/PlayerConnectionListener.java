package org.misqzy.flectonegrowth.bukkit.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.bukkit.GrowthBlockCache;
import org.misqzy.flectonegrowth.bukkit.UpdateNotifier;

import java.util.UUID;

/**
 * Drives the growth engine's player lifecycle from Bukkit events, plus a
 * respawn hook the original plugin did not have (see
 * {@code docs/ARCHITECTURE.md} "Bugs fixed" #5).
 */
public final class PlayerConnectionListener implements Listener {

    private final JavaPlugin plugin;
    private final GrowthEngine growthEngine;
    private final UpdateNotifier updateNotifier;

    public PlayerConnectionListener(JavaPlugin plugin, GrowthEngine growthEngine, UpdateNotifier updateNotifier) {
        this.plugin = plugin;
        this.growthEngine = growthEngine;
        this.updateNotifier = updateNotifier;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        growthEngine.loadPlayer(new BukkitPlayerAdapter(event.getPlayer()), null);
        updateNotifier.notifyIfPending(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        growthEngine.unloadPlayer(uuid);
        GrowthBlockCache.forget(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // The scale attribute's base value can be reset back to default as
        // part of resolving the respawn itself, so re-applying in the same
        // tick can be undone a moment later; scheduling one tick later is
        // what actually sticks on affected server versions.
        Bukkit.getScheduler().runTask(plugin, () ->
                growthEngine.applyScale(new BukkitPlayerAdapter(event.getPlayer())));
    }
}
