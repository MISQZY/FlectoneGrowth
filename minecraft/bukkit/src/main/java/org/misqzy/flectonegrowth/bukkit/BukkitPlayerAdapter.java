package org.misqzy.flectonegrowth.bukkit;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;

import java.util.UUID;

/**
 * Wraps a live Bukkit {@link Player} as a {@link PlatformPlayer}. Created
 * fresh per call site rather than cached, since it is a thin, stateless view
 * over the live Bukkit object - caching it would risk holding a stale
 * reference across a respawn/teleport.
 */
public final class BukkitPlayerAdapter implements PlatformPlayer {

    private final Player player;

    public BukkitPlayerAdapter(Player player) {
        this.player = player;
    }

    public Player bukkit() {
        return player;
    }

    @Override public UUID uuid() { return player.getUniqueId(); }
    @Override public String name() { return player.getName(); }
    @Override public boolean isOnline() { return player.isOnline(); }
    @Override public boolean hasPermission(String permission) { return player.hasPermission(permission); }
    @Override public void sendMessage(Component component) { LegacyText.send(player, component); }

    @Override
    public long playedSeconds() {
        return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    }

    @Override
    public Double currentScale() {
        AttributeInstance attr = player.getAttribute(Attribute.SCALE);
        return attr != null ? attr.getBaseValue() : null;
    }

    @Override
    public void applyScale(double scale) {
        AttributeInstance attr = player.getAttribute(Attribute.SCALE);
        if (attr != null) attr.setBaseValue(scale);
    }

    @Override
    public boolean isGrowthBlocked() {
        Location loc = player.getLocation();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        World world = player.getWorld();

        // The actual scan only runs when the player's block position (or
        // world) differs from the last check - see GrowthBlockCache.
        return GrowthBlockCache.compute(player.getUniqueId(), world.getUID(), bx, by, bz,
                () -> scanForSolidBlocks(world, bx, by, bz));
    }

    /**
     * Single pass over the 3x3 column at foot/head level, skipping only the
     * player's own feet block - 17 {@code world.getBlockAt} calls, including
     * the block above the head, without querying it twice or re-resolving
     * {@code world} on every call. Only invoked by {@link GrowthBlockCache}
     * when the position it's keyed on has actually changed.
     */
    private static boolean scanForSolidBlocks(World world, int bx, int by, int bz) {
        for (int y = by; y <= by + 1; y++) {
            for (int x = bx - 1; x <= bx + 1; x++) {
                for (int z = bz - 1; z <= bz + 1; z++) {
                    if (x == bx && y == by && z == bz) continue;
                    if (isSolid(world.getBlockAt(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    private static boolean isSolid(Block block) {
        Material mat = block.getType();
        return !mat.isAir() && mat.isSolid();
    }
}
