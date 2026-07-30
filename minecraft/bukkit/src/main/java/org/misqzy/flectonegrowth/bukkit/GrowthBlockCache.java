package org.misqzy.flectonegrowth.bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Per-player cache for {@link BukkitPlayerAdapter#isGrowthBlocked()}.
 *
 * <p>{@code GrowthEngine.tick()} calls {@code isGrowthBlocked()} once per
 * online player every growth tick, but the underlying block scan (~17
 * {@code world.getBlockAt} calls) only needs to actually re-run when the
 * player has moved to a different block since the last check - a stationary
 * player (AFK, working, fighting) gets the cached answer instead of repaying
 * the scan on every tick, the common case since growth ticks fire far more
 * often than most players change block position.
 *
 * <p><b>Trade-off, chosen deliberately</b>: a block placed or broken right
 * next to a player who hasn't changed block position is only picked up the
 * next time they move (or rejoin), not on the very next tick. Growth pausing
 * is a gradual, cosmetic effect, not something that needs frame-perfect
 * reaction to a world edit, so this bounded staleness is an acceptable price
 * for skipping the scan while the player stands still.</p>
 *
 * <p>A static cache rather than a Guice-managed instance because
 * {@link BukkitPlayerAdapter} itself is deliberately not injected - it's
 * constructed fresh with {@code new} at every call site across this module
 * (listeners, {@code GrowthTicker}, commands, ...), so there's no single
 * owner to hand a per-player cache instance to without threading a new
 * constructor parameter through every one of those call sites.</p>
 */
public final class GrowthBlockCache {

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    private GrowthBlockCache() {}

    /** Returns the cached result if {@code world}/{@code x}/{@code y}/{@code z} still matches {@code uuid}'s last check, otherwise runs {@code scan} and caches its result. */
    static boolean compute(UUID uuid, UUID world, int x, int y, int z, BooleanSupplier scan) {
        Entry cached = CACHE.get(uuid);
        if (cached != null && cached.matches(world, x, y, z)) return cached.blocked;

        boolean blocked = scan.getAsBoolean();
        CACHE.put(uuid, new Entry(world, x, y, z, blocked));
        return blocked;
    }

    /** Forgets {@code uuid}'s cached result - call on quit so a stale entry doesn't linger for a player who's no longer online. */
    public static void forget(UUID uuid) {
        CACHE.remove(uuid);
    }

    private record Entry(UUID world, int x, int y, int z, boolean blocked) {
        boolean matches(UUID world, int x, int y, int z) {
            return this.x == x && this.y == y && this.z == z && this.world.equals(world);
        }
    }
}
