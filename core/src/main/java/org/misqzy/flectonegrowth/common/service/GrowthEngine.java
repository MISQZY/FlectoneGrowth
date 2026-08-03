package org.misqzy.flectonegrowth.common.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.config.CoreConfig;
import org.misqzy.flectonegrowth.common.domain.Gender;
import org.misqzy.flectonegrowth.common.domain.GenderRegistry;
import org.misqzy.flectonegrowth.common.domain.OfflineProfile;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.domain.PlayerProfile;
import org.misqzy.flectonegrowth.common.domain.ProfileCache;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.network.SyncMessage;
import org.misqzy.flectonegrowth.common.platform.Platform;
import org.misqzy.flectonegrowth.common.platform.PlatformPlayer;
import org.misqzy.flectonegrowth.common.platform.PlayerLookup;
import org.misqzy.flectonegrowth.common.storage.Storage;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.DoubleConsumer;

/**
 * Coordinates growth/gender/custom-scale state. This is the direct
 * successor of the original {@code FlectoneGrowthService}, but it no longer
 * touches Bukkit types directly - everything it needs comes through
 * {@link PlatformPlayer}, {@link Storage} and {@link NetworkMessenger}, so
 * the whole class is unit-testable without a server and reusable if a
 * second entity-capable platform is ever added.
 */
@Singleton
public final class GrowthEngine {

    /** Hard floor under any configured {@code minScale()} - a scale of 0 or negative would make a player invisible/broken client-side. */
    private static final double ABSOLUTE_MIN_SCALE = 0.01;
    /** Hard floor under any configured tick interval, matching the shortest interval a Minecraft server scheduler can meaningfully honor (one tick). */
    private static final long MIN_TICK_INTERVAL_MILLIS = 50L;

    private final Platform platform;
    private final ProfileCache profiles;
    private final NetworkMessenger messenger;
    private final PlayerLookup playerLookup;

    private volatile CoreConfig config;
    private volatile Storage storage;
    @Getter
    @Accessors(fluent = true)
    private volatile GenderRegistry genderRegistry;
    private volatile GrowthTimeAssigner timeAssigner;
    private volatile PlayTimeTracker playTimeTracker;

    @Inject
    public GrowthEngine(Platform platform, ProfileCache profiles, NetworkMessenger messenger, PlayerLookup playerLookup,
                         CoreConfig config, Storage storage, GenderRegistry genderRegistry) {
        this.platform = platform;
        this.profiles = profiles;
        this.messenger = messenger;
        this.playerLookup = playerLookup;
        this.config = config;
        this.storage = storage;
        this.genderRegistry = genderRegistry;
        this.timeAssigner = new GrowthTimeAssigner(config, storage);
        this.playTimeTracker = new PlayTimeTracker(storage);

        messenger.onReceive(this::applyIncomingSync);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Whether the periodic tick should run at all. The engine does not own
     * the repeating task itself - gathering "all online players" is a
     * platform concern ({@code Bukkit.getOnlinePlayers()} and friends), so
     * the platform module owns the {@link Scheduler}
     * timer and calls {@link #tick(List)} on each firing.
     */
    public boolean autoGrowthEnabled() {
        return config.autoGrowth();
    }

    public long tickIntervalMillis() {
        return Math.max(MIN_TICK_INTERVAL_MILLIS, (long) (config.growthUpdateIntervalSeconds() * 1000L));
    }

    /** Hot-swaps config/storage/gender registry after a reload; safe to call from any thread (fields are volatile). */
    public void applyReload(CoreConfig newConfig, Storage newStorage, GenderRegistry newGenderRegistry) {
        this.config = newConfig;
        this.storage = newStorage;
        this.genderRegistry = newGenderRegistry;
        this.timeAssigner = new GrowthTimeAssigner(newConfig, newStorage);
        this.playTimeTracker = new PlayTimeTracker(newStorage);
    }

    /** Called once per tick (main thread) with the currently online players. */
    public void tick(List<PlatformPlayer> onlinePlayers) {
        for (PlatformPlayer player : onlinePlayers) {
            // A profile only exists once loadPlayer's async load creates it -
            // skip a player still mid-join rather than let applyScale's own
            // getOrCreate hand back a fresh profile whose targetGrowthSeconds
            // defaults to -1 ("not yet loaded"), which applyScale/isAtMaxGrowth
            // treat the same as "instant growth" and force to max scale for
            // one tick until the real load corrects it a moment later.
            if (profiles.get(player.uuid()) == null) continue;
            // Cheap in-memory check first: a player already at max growth
            // never needs applyScale regardless of isGrowthBlocked, so there's
            // no reason to pay for that block scan's world queries just to
            // throw the result away. Independent conditions, same net skip
            // decision either order - only the amount of work differs.
            if (isAtMaxGrowth(player)) continue;
            if (config.pauseWhenBoxedIn() && player.isGrowthBlocked()) continue;
            applyScale(player);
        }
    }

    // -----------------------------------------------------------------------
    // Player lifecycle
    // -----------------------------------------------------------------------

    /**
     * Loads a joining player's persisted state. Call {@code onLoaded} back on
     * the main thread.
     *
     * <p>Also called (with {@code onLoaded == null}) by {@link #refreshAfterReload}
     * for every already-online player - {@code freshLoad} below distinguishes
     * that case from a genuine join so {@link PlayTimeTracker#recordJoin} (which
     * bumps the persisted session count) only ever fires on a real join,
     * not on every reload.</p>
     *
     * <p>The DB-backed playtime tracker is only ever consulted when
     * {@link CoreConfig#networkSyncEnabled()} is on - without a synced
     * network there's no shared record to keep consistent, so growth just
     * reads {@link PlatformPlayer#playedSeconds()} directly (see
     * {@link #playedSeconds}) and this whole block is skipped, avoiding
     * needless DB writes on every join/quit for the common single-server
     * case. When sync is on but {@link CoreConfig#networkIncludeServer()} is
     * false for this server (e.g. a hub/lobby), a genuine
     * join still only reads ({@link PlayTimeTracker#loadInto}), never
     * records ({@link PlayTimeTracker#recordJoin}) - time spent here must
     * not bump {@code last_seen}/{@code sessions} or it would count toward
     * play_time the moment the player leaves.</p>
     */
    public void loadPlayer(PlatformPlayer player, Runnable onLoaded) {
        platform.scheduler().runAsync(() -> {
            UUID uuid = player.uuid();
            boolean freshLoad = profiles.get(uuid) == null;
            PlayerProfile profile = profiles.getOrCreate(uuid, genderRegistry.getDefault());

            Double storedScale = storage.getCustomScale(uuid);
            profile.setCustomScale(storedScale);

            String genderKey = storage.getGenderKey(uuid);
            profile.setGender(genderRegistry.resolve(genderKey));

            timeAssigner.loadInto(profile);

            if (config.networkSyncEnabled()) {
                String server = serverKey();
                if (freshLoad && config.networkIncludeServer()) playTimeTracker.recordJoin(profile, server);
                else playTimeTracker.loadInto(profile, server);
            }

            platform.scheduler().runSync(() -> {
                if (!player.isOnline()) {
                    // Player quit while this async load was in flight - unloadPlayer already
                    // ran (and, finding no profile yet, skipped its checkpoint) before the
                    // getOrCreate above created one. removeIfSame cleans it up now instead of
                    // leaving a permanent ghost entry (nothing else will ever call
                    // unloadPlayer for this uuid again) - conditioned on identity rather than
                    // a blind remove(uuid), since a fast quit-then-rejoin may already have
                    // replaced this entry with the new session's own freshly-loaded profile,
                    // which must not be deleted out from under it.
                    profiles.removeIfSame(uuid, profile);
                    return;
                }
                applyScale(player);
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /**
     * Checkpoints the player's accumulated playtime, then evicts their
     * cached profile. Skipped entirely when sync is off (nothing was ever
     * recorded to checkpoint) or this server is blocklisted (this session's
     * time must not count).
     */
    public void unloadPlayer(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        profiles.remove(uuid);
        if (!config.networkSyncEnabled() || !config.networkIncludeServer()) return;
        if (profile == null || profile.lastCheckpointSeconds() < 0) return;

        long now = Instant.now().getEpochSecond();
        long newTotal = profile.totalPlayedSeconds() + (now - profile.lastCheckpointSeconds());
        PlayTimeTracker tracker = playTimeTracker;
        String server = serverKey();
        platform.scheduler().runAsync(() -> tracker.checkpoint(uuid, server, newTotal, now));
    }

    // -----------------------------------------------------------------------
    // Scale
    // -----------------------------------------------------------------------

    public double minScale() {
        return Math.max(ABSOLUTE_MIN_SCALE, Math.min(config.minScale(), config.maxScale()));
    }

    /** The scale a player is actually at right now - their custom/growth-derived attribute value if set, else {@link #minScale()} as the starting point before it's ever been applied. */
    public double effectiveScale(PlatformPlayer player) {
        Double scale = player.currentScale();
        return scale != null ? scale : minScale();
    }

    public double maxScaleFor(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        return maxScaleFor(profile != null ? profile.gender() : genderRegistry.getDefault());
    }

    public double maxScaleFor(Gender gender) {
        return genderRegistry.getMaxScaleFor(gender);
    }

    public Gender genderOf(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        return profile != null ? profile.gender() : genderRegistry.getDefault();
    }

    /**
     * Current played seconds driving growth math.
     *
     * <p>Without a synced network ({@link CoreConfig#networkSyncEnabled()}
     * off), falls back to {@link PlatformPlayer#playedSeconds()} - the
     * server's own local statistic - exactly as before centralized playtime
     * tracking existed; there's no shared record to keep consistent with a
     * single server. With sync on, reads the DB-backed
     * {@code total + (now - last)} reconstruction instead, mirroring
     * FlectonePulse's own {@code TOTAL_DYNAMIC} - unless
     * {@link CoreConfig#networkIncludeServer()} is false for this running
     * server, in which case time spent here is frozen out: the persisted
     * total is returned as-is, with no live elapsed-time addition.</p>
     */
    private long playedSeconds(PlatformPlayer player, PlayerProfile profile) {
        if (!config.networkSyncEnabled()) return player.playedSeconds();
        if (profile == null) return 0L;
        if (!config.networkIncludeServer()) return profile.totalPlayedSeconds();
        if (profile.lastCheckpointSeconds() < 0) return 0L;
        return profile.totalPlayedSeconds() + (Instant.now().getEpochSecond() - profile.lastCheckpointSeconds());
    }

    /** The playtime bucket key: this server's own id under {@link CoreConfig#networkPerServer()}, or {@code ""} for the one shared network-wide bucket. */
    private String serverKey() {
        return config.networkPerServer() ? platform.serverId() : "";
    }

    public boolean isAtMaxGrowth(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        if (profile == null || profile.hasCustomScale()) return false;

        Double scale = player.currentScale();
        if (scale == null) return false;

        double max = maxScaleFor(player);
        long target = profile.targetGrowthSeconds();
        if (target <= 0) return scale >= max;

        double progress = ScaleMath.progress(playedSeconds(player, profile), target);
        return scale >= max && progress >= 1.0;
    }

    public long secondsUntilFullGrowth(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        long target = profile != null ? profile.targetGrowthSeconds() : config.growTimeSeconds();
        return ScaleMath.secondsRemaining(playedSeconds(player, profile), target);
    }

    /** Progress toward this player's natural growth target, in {@code [0.0, 1.0]}. Custom-scale players have no growth curve to track, so they read as fully progressed. */
    public double growthProgress(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        if (profile == null) return 0.0;
        if (profile.hasCustomScale()) return 1.0;

        long target = profile.targetGrowthSeconds();
        if (target <= 0) return 1.0;

        return ScaleMath.progress(playedSeconds(player, profile), target);
    }

    public boolean hasCustomScale(PlatformPlayer player) {
        PlayerProfile profile = profiles.get(player.uuid());
        return profile != null && profile.hasCustomScale();
    }

    /** Recomputes and applies the effective scale for one online player. Main thread only. */
    public void applyScale(PlatformPlayer player) {
        PlayerProfile profile = profiles.getOrCreate(player.uuid(), genderRegistry.getDefault());
        Double custom = profile.customScale();

        if (custom != null) {
            player.applyScale(Math.min(custom, maxScaleFor(player)));
            return;
        }

        double max = maxScaleFor(player);
        long target = profile.targetGrowthSeconds();
        if (target <= 0) {
            player.applyScale(max);
            return;
        }

        double progress = ScaleMath.progress(playedSeconds(player, profile), target);
        player.applyScale(ScaleMath.scaleAtProgress(progress, minScale(), max));
    }

    /**
     * Re-derives every online player's cached growth-timing/custom-scale/
     * gender state from the just-reloaded config/storage, then reapplies
     * scale - call after {@link FlectoneGrowthCore#reload}
     * so growth-setting changes (e.g. {@code growth.time-minutes}) take
     * effect immediately instead of only on a player's next join.
     *
     * <p>{@link #applyScale} alone isn't enough here: it reads
     * {@link PlayerProfile#targetGrowthSeconds()} as-is, and that field is
     * only ever populated once, at {@link #loadPlayer}. Without re-running
     * {@link #loadPlayer}, an already-online player's target growth time
     * stays whatever it was computed as under the pre-reload config until
     * they relog - which was the actual bug being fixed here, not just a
     * missing scale refresh.</p>
     */
    public void refreshAfterReload(List<PlatformPlayer> onlinePlayers) {
        for (PlatformPlayer player : onlinePlayers) loadPlayer(player, null);
    }

    /**
     * @param onSuccess receives the scale actually applied - not always the
     *                  raw {@code scale} passed in, since a value just
     *                  outside {@code [min, max]} only because of
     *                  {@link ScaleMath#formatValue}'s display rounding is
     *                  clamped to the boundary rather than rejected, see
     *                  {@link ScaleMath#clampToRange}.
     */
    public void setCustomScale(PlatformPlayer player, double scale, DoubleConsumer onSuccess, Runnable onFailure) {
        double min = minScale();
        double max = maxScaleFor(player);
        OptionalDouble clamped = ScaleMath.clampToRange(scale, min, max);
        if (clamped.isEmpty()) {
            if (onFailure != null) onFailure.run();
            return;
        }
        double finalScale = clamped.getAsDouble();

        UUID uuid = player.uuid();
        // Captured before dispatching async, not re-fetched fresh inside the runSync callback:
        // see the identity check below for why.
        PlayerProfile profileAtCallTime = profiles.get(uuid);
        platform.scheduler().runAsync(() -> {
            boolean ok = storage.setCustomScale(uuid, finalScale);
            platform.scheduler().runSync(() -> {
                if (ok) {
                    // Only mutate if this exact profile instance is still the one cached for
                    // uuid. Two distinct races share this one guard: (1) the player quit while
                    // the async storage write was in flight - unloadPlayer already evicted
                    // their profile, so profiles.get(uuid) is now null (or a fresh instance,
                    // see (2)) and mutating/resurrecting profileAtCallTime here would leave a
                    // permanent ghost entry that skips PlayTimeTracker.recordJoin on their next
                    // real join; (2) the player quit *and rejoined* before this callback ran -
                    // their new session's own loadPlayer already replaced this uuid's entry
                    // with a brand-new PlayerProfile, and applying this stale write to it would
                    // silently clobber whatever the new session just loaded from storage. The
                    // storage write already persisted either way, so there's nothing lost by
                    // skipping the in-memory update here.
                    if (profileAtCallTime != null && profiles.get(uuid) == profileAtCallTime) {
                        profileAtCallTime.setCustomScale(finalScale);
                        if (player.isOnline()) player.applyScale(finalScale);
                    }
                    messenger.broadcast(SyncMessage.scaleSet(uuid, finalScale, platform.serverId()));
                    if (onSuccess != null) onSuccess.accept(finalScale);
                } else if (onFailure != null) {
                    onFailure.run();
                }
            });
        });
    }

    public void removeCustomScale(PlatformPlayer player, Runnable onSuccess, Runnable onFailure) {
        UUID uuid = player.uuid();
        // See setCustomScale's comment on profileAtCallTime for why this is captured up front
        // and compared by identity rather than re-fetched inside the runSync callback.
        PlayerProfile profileAtCallTime = profiles.get(uuid);
        platform.scheduler().runAsync(() -> {
            boolean ok = storage.removeCustomScale(uuid);
            platform.scheduler().runSync(() -> {
                if (ok) {
                    if (profileAtCallTime != null && profiles.get(uuid) == profileAtCallTime) {
                        profileAtCallTime.setCustomScale(null);
                        if (player.isOnline()) applyScale(player);
                    }
                    messenger.broadcast(SyncMessage.scaleRemoved(uuid, platform.serverId()));
                    if (onSuccess != null) onSuccess.run();
                } else if (onFailure != null) {
                    onFailure.run();
                }
            });
        });
    }

    // -----------------------------------------------------------------------
    // Gender
    // -----------------------------------------------------------------------

    public void setGender(PlatformPlayer player, Gender gender, Runnable onSuccess, Runnable onFailure) {
        UUID uuid = player.uuid();
        // See setCustomScale's comment on profileAtCallTime for why this is captured up front
        // and compared by identity rather than re-fetched inside the runSync callback.
        PlayerProfile profileAtCallTime = profiles.get(uuid);
        platform.scheduler().runAsync(() -> {
            boolean ok = storage.setGenderKey(uuid, gender.key());
            if (!ok) {
                platform.scheduler().runSync(() -> { if (onFailure != null) onFailure.run(); });
                return;
            }

            double newMax = genderRegistry.getMaxScaleFor(gender);
            boolean scaleClamped = profileAtCallTime != null
                    && clampCustomScaleForNewGender(uuid, gender, profileAtCallTime.customScale(), newMax);

            platform.scheduler().runSync(() -> {
                if (profileAtCallTime != null && profiles.get(uuid) == profileAtCallTime) {
                    profileAtCallTime.setGender(gender);
                    if (scaleClamped) profileAtCallTime.setCustomScale(newMax);
                    if (player.isOnline()) applyScale(player);
                }
                messenger.broadcast(SyncMessage.genderSet(uuid, gender.key(), platform.serverId()));
                if (onSuccess != null) onSuccess.run();
            });
        });
    }

    /**
     * If {@code currentCustom} now exceeds {@code newMax} under the player's
     * new gender, persists a clamp down to {@code newMax} and reports
     * whether that succeeded - {@link #applyScale} already clamps
     * <em>visually</em> via {@link #maxScaleFor}, but the stored value
     * itself needs correcting too, or a later {@code /height} read (or the
     * raw DB) would show a custom scale above the new cap.
     *
     * @return {@code true} only if a clamp was both needed and successfully
     *         persisted - the only case the caller should also update the
     *         in-memory profile's custom scale.
     */
    private boolean clampCustomScaleForNewGender(UUID uuid, Gender gender, Double currentCustom, double newMax) {
        if (currentCustom == null || currentCustom <= newMax) return false;

        boolean persisted = storage.setCustomScale(uuid, newMax);
        if (!persisted) {
            // The gender write already committed by this point, so this
            // can't be rolled back into a clean failure - but silently
            // continuing would hide that the player's persisted custom
            // scale is now above their new gender's cap until an admin
            // happens to notice via /height or the raw DB. Surface it.
            platform.logger().warning("setGender(" + uuid + ", " + gender.key() + "): gender saved, but "
                    + "clamping the existing custom scale (" + currentCustom + ") down to the new max ("
                    + newMax + ") failed to persist - the stored custom scale now exceeds this gender's cap "
                    + "until it's re-set or storage is reachable again.");
        }
        return persisted;
    }

    // -----------------------------------------------------------------------
    // Offline lookups
    // -----------------------------------------------------------------------

    /**
     * Builds a one-shot {@link OfflineProfile} snapshot straight from
     * storage, for callers that need a player's growth state without them
     * being online (e.g. PlaceholderAPI backing an offline leaderboard or
     * hologram) - {@link #profiles} only ever holds online players.
     *
     * <p><b>Blocking</b> - this does real storage I/O synchronously. That's
     * a deliberate exception to every other {@code Storage} call in this
     * class going through {@link Scheduler#runAsync}:
     * PlaceholderAPI's {@code onRequest} has no async contract to hook into,
     * it must return a {@code String} immediately, so there's no offloading
     * this without caching. Fine for occasional lookups; not meant for a
     * hot path iterating many offline players at once.</p>
     */
    public OfflineProfile loadOffline(UUID uuid) {
        Gender gender = genderRegistry.resolve(storage.getGenderKey(uuid));
        Double customScale = storage.getCustomScale(uuid);

        long targetGrowthSeconds = config.growTimeSeconds();
        if (config.isRangeMode()) {
            Long stored = storage.getGrowthTimeSeconds(uuid);
            if (stored != null) targetGrowthSeconds = stored;
        }

        long playedSeconds = 0L;
        if (config.networkSyncEnabled()) {
            PlayTime pt = storage.getPlayTime(uuid, serverKey());
            // Offline, so no live (now - last) addition - total is already
            // the full checkpointed figure as of their last quit.
            if (pt != null) playedSeconds = pt.total();
        }

        return new OfflineProfile(gender, customScale, targetGrowthSeconds, playedSeconds);
    }

    /**
     * Adds {@code secondsToAdd} on top of {@code uuid}'s existing playtime
     * total in this server's own {@link #serverKey() bucket} (creating the
     * row on their first record) rather than overwriting it - used by the
     * {@code /fg migrate playtime} admin command to seed this plugin's
     * tracking from a platform's own built-in playtime statistic. A no-op
     * (returns {@code false}) for a non-positive amount, since there's
     * nothing meaningful to add.
     *
     * <p><b>Blocking</b> - same contract as every other {@link Storage} call
     * in this class (see {@link #loadOffline}); a caller migrating many
     * players at once must run this off the main thread itself.</p>
     */
    public boolean addPlaytimeSeconds(UUID uuid, long secondsToAdd) {
        if (secondsToAdd <= 0) return false;
        return storage.addPlayTime(uuid, serverKey(), secondsToAdd, Instant.now().getEpochSecond());
    }

    /** The scale {@code profile} would be at if applied right now - mirrors {@link #applyScale}'s math exactly, since an offline player's played seconds are frozen (no live tick to drift from this snapshot). */
    public double effectiveScale(OfflineProfile profile) {
        double max = maxScaleFor(profile.gender());
        if (profile.hasCustomScale()) return Math.min(profile.customScale(), max);
        if (profile.targetGrowthSeconds() <= 0) return max;
        return ScaleMath.scaleAtProgress(growthProgress(profile), minScale(), max);
    }

    /** See {@link #growthProgress(PlatformPlayer)} - same math, offline snapshot. */
    public double growthProgress(OfflineProfile profile) {
        if (profile.hasCustomScale() || profile.targetGrowthSeconds() <= 0) return 1.0;
        return ScaleMath.progress(profile.playedSeconds(), profile.targetGrowthSeconds());
    }

    /** See {@link #secondsUntilFullGrowth(PlatformPlayer)} - same math, offline snapshot. */
    public long secondsUntilFullGrowth(OfflineProfile profile) {
        return ScaleMath.secondsRemaining(profile.playedSeconds(), profile.targetGrowthSeconds());
    }

    /** See {@link #isAtMaxGrowth(PlatformPlayer)} - same math, offline snapshot. */
    public boolean isAtMaxGrowth(OfflineProfile profile) {
        if (profile.hasCustomScale()) return false;
        double current = effectiveScale(profile);
        double max = maxScaleFor(profile.gender());
        if (profile.targetGrowthSeconds() <= 0) return current >= max;
        return current >= max && growthProgress(profile) >= 1.0;
    }

    // -----------------------------------------------------------------------
    // Network sync (incoming)
    // -----------------------------------------------------------------------

    private void applyIncomingSync(SyncMessage message) {
        if (platform.serverId().equals(message.sourceServerId())) return; // echo guard

        platform.scheduler().runSync(() -> {
            PlayerProfile profile = profiles.get(message.playerUuid());
            if (profile == null) return; // player not online on this server, nothing to refresh

            switch (message.type()) {
                case SCALE_SET -> profile.setCustomScale(message.doubleValue());
                case SCALE_REMOVED -> profile.setCustomScale(null);
                case GENDER_SET -> profile.setGender(genderRegistry.resolve(message.stringValue()));
            }

            PlatformPlayer online = playerLookup.find(message.playerUuid());
            if (online != null && online.isOnline()) applyScale(online);
        });
    }
}
