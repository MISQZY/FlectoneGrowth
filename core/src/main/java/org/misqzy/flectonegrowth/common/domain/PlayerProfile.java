package org.misqzy.flectonegrowth.common.domain;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory state for one online player. Replaces the three separate
 * customScales / genderCache / growthTimeCache maps the original plugin
 * kept in three unrelated classes - one profile per player is easier to
 * reason about and to invalidate on cross-server sync.
 *
 * <p>Getters are Lombok-generated ({@link Getter} + fluent {@link Accessors})
 * except {@link #customScale()} (unwraps the backing {@link AtomicReference},
 * not a raw field return). Setters stay hand-written: this class's existing
 * convention is bare-name fluent getters paired with {@code set}-prefixed
 * setters, a mix {@code @Accessors(fluent = true)} can't reproduce (it
 * renames both symmetrically). The constructor also stays hand-written -
 * only 2 of the 7 fields are constructor parameters, the rest keep their own
 * field-initializer defaults, which neither {@code @RequiredArgsConstructor}
 * nor {@code @AllArgsConstructor} can express.</p>
 */
public final class PlayerProfile {

    @Getter
    @Accessors(fluent = true)
    private final UUID uuid;
    private final AtomicReference<Double> customScale = new AtomicReference<>(null);
    @Getter
    @Accessors(fluent = true)
    private volatile Gender gender;
    /** -1 means "not yet assigned/loaded". */
    @Getter
    @Accessors(fluent = true)
    private volatile long targetGrowthSeconds = -1;

    /** Accumulated played seconds as of {@link #lastCheckpointSeconds()} - excludes time elapsed since then. */
    @Getter
    @Accessors(fluent = true)
    private volatile long totalPlayedSeconds = 0;
    /** Epoch second of the last persisted checkpoint (join or quit). {@code -1} means "not yet loaded". */
    @Getter
    @Accessors(fluent = true)
    private volatile long lastCheckpointSeconds = -1;
    /** Epoch second of this player's first-ever join. {@code -1} means "not yet loaded". */
    @Getter
    @Accessors(fluent = true)
    private volatile long firstJoinSeconds = -1;
    /** Number of times this player has joined. */
    @Getter
    @Accessors(fluent = true)
    private volatile int sessions = 0;

    public PlayerProfile(UUID uuid, Gender initialGender) {
        this.uuid = uuid;
        this.gender = initialGender;
    }

    public Double customScale() {
        return customScale.get();
    }

    public void setCustomScale(Double scale) {
        customScale.set(scale);
    }

    public boolean hasCustomScale() {
        return customScale.get() != null;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public void setTargetGrowthSeconds(long seconds) {
        this.targetGrowthSeconds = seconds;
    }

    /** Hydrates the playtime fields from a freshly loaded/upserted {@link PlayTime} row. */
    public void setPlayTime(long total, long last, long first, int sessions) {
        this.totalPlayedSeconds = total;
        this.lastCheckpointSeconds = last;
        this.firstJoinSeconds = first;
        this.sessions = sessions;
    }
}
