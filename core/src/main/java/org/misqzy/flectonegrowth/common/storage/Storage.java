package org.misqzy.flectonegrowth.common.storage;

import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.domain.StoredPlayerData;

import java.util.UUID;

/**
 * Data-access contract. All methods are synchronous and blocking - callers
 * are expected to invoke them from an async thread (see
 * {@link Scheduler#runAsync}), the
 * same contract the original plugin used. Kept synchronous deliberately:
 * the growth engine already funnels every call through one executor, so a
 * CompletableFuture wrapper at this layer would only add indirection.
 */
public interface Storage {

    boolean initialize();

    boolean testConnection();

    StorageType type();

    void close();

    // Custom scale (manually set via /height <value>)
    Double getCustomScale(UUID uuid);
    boolean setCustomScale(UUID uuid, double scale);
    boolean removeCustomScale(UUID uuid);

    // Gender
    String getGenderKey(UUID uuid);
    boolean setGenderKey(UUID uuid, String genderKey);

    // Individually-assigned growth time (range mode)
    Long getGrowthTimeSeconds(UUID uuid);
    boolean setGrowthTimeSeconds(UUID uuid, long seconds);

    // Playtime tracking (first/last/total/sessions per player - modeled on
    // FlectonePulse's own scheme, see ARCHITECTURE.md "Playtime tracking").
    // Each row is keyed by (uuid, server) - `server` is "" for the shared
    // network-wide bucket (network.per-server: false) or the running
    // server's own id when tracking a per-server bucket (network.per-server:
    // true) - see GrowthEngine's serverKey().
    PlayTime getPlayTime(UUID uuid, String server);
    /** Creates the row on a brand new player (first=last=now, total=0, sessions=1); otherwise bumps last/sessions only, leaving first/total untouched. */
    boolean recordJoin(UUID uuid, String server, long nowEpochSeconds);
    /** Persists an updated running total and resets the checkpoint - used at quit. */
    boolean checkpointPlayTime(UUID uuid, String server, long totalSeconds, long nowEpochSeconds);
    /**
     * Adds {@code secondsToAdd} on top of any existing {@code total_seconds}
     * (creating the row if absent: first=last=now, sessions=0), unlike
     * {@link #checkpointPlayTime}'s absolute overwrite - used by playtime
     * migration/import tooling (e.g. seeding this tracking from a platform's
     * own built-in playtime statistic) that must never clobber a total
     * that's already being tracked.
     */
    boolean addPlayTime(UUID uuid, String server, long secondsToAdd, long nowEpochSeconds);

    /**
     * Scale, gender, growth time and the {@code server} playtime bucket in one
     * round-trip. The default just composes the individual getters (fine for
     * YAML); SQL backends override it with a single joined query. A backend
     * that fails to read returns {@code null} (as opposed to
     * {@link StoredPlayerData#EMPTY} for "no rows"), so callers can avoid
     * writing defaults over data they simply failed to read.
     */
    default StoredPlayerData loadStoredData(UUID uuid, String server) {
        return new StoredPlayerData(getCustomScale(uuid), getGenderKey(uuid), getGrowthTimeSeconds(uuid), getPlayTime(uuid, server));
    }

    // Player identity (backing table every other one now references by FK -
    // see the "player" table). Get-or-create semantics: any of the above
    // writes will silently no-op until a player row exists for that uuid.
    /** Get-or-create the player row for {@code uuid} and (re)stamp its username - idempotent, safe to call on every join. */
    boolean upsertUsername(UUID uuid, String username);
    /** Last-known username for {@code uuid}, or {@code null} if never captured (e.g. a migration-backfilled row nobody has rejoined under yet). */
    String getUsername(UUID uuid);
    /** Case-insensitive username -> uuid lookup, or {@code null} if no player row matches. */
    UUID findUuidByUsername(String username);
}
