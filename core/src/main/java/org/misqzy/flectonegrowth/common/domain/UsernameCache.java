package org.misqzy.flectonegrowth.common.domain;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory hint for username <-> uuid lookups, backing
 * {@code GrowthEngine#resolveUuidByUsername} - a fast path only, never the
 * source of truth (that's {@code Storage}'s {@code player} table). Never
 * persisted or warmed from storage at startup: a cold cache (fresh restart,
 * or a player who hasn't rejoined since) just falls through to a DB lookup,
 * same cost as if this cache didn't exist.
 */
public final class UsernameCache {

    private final Map<UUID, String> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byLowerName = new ConcurrentHashMap<>();

    public void put(UUID uuid, String username) {
        String previous = byUuid.put(uuid, username);
        if (previous != null && !previous.equalsIgnoreCase(username)) {
            byLowerName.remove(previous.toLowerCase(Locale.ROOT));
        }
        byLowerName.put(username.toLowerCase(Locale.ROOT), uuid);
    }

    /** Whether {@code uuid} is already known (and still the owner of the name lookup) under exactly {@code username}. */
    public boolean isCurrent(UUID uuid, String username) {
        return username.equals(byUuid.get(uuid)) && uuid.equals(byLowerName.get(username.toLowerCase(Locale.ROOT)));
    }

    public void clear() {
        byUuid.clear();
        byLowerName.clear();
    }

    public String get(UUID uuid) {
        return byUuid.get(uuid);
    }

    public UUID findUuid(String username) {
        return username == null ? null : byLowerName.get(username.toLowerCase(Locale.ROOT));
    }
}
