package org.misqzy.flectonegrowth.common.domain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe registry of {@link PlayerProfile}s for currently online players. */
public final class ProfileCache {

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public PlayerProfile getOrCreate(UUID uuid, Gender defaultGender) {
        return profiles.computeIfAbsent(uuid, id -> new PlayerProfile(id, defaultGender));
    }

    public PlayerProfile get(UUID uuid) {
        return profiles.get(uuid);
    }

    public void remove(UUID uuid) {
        profiles.remove(uuid);
    }

    /**
     * Removes {@code uuid}'s cached profile only if it's still exactly
     * {@code expected} - a no-op if it's already been replaced (e.g. the
     * player quit and rejoined, and their new session's own load already
     * overwrote this entry with a fresh {@link PlayerProfile}) or already
     * removed. Backed by {@link Map#remove(Object, Object)}'s atomic
     * check-and-remove so a caller can safely clean up a profile it created
     * without racing a concurrent load for the same uuid on another thread.
     */
    public void removeIfSame(UUID uuid, PlayerProfile expected) {
        profiles.remove(uuid, expected);
    }

    public void clear() {
        profiles.clear();
    }
}
