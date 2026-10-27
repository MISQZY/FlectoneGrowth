package org.misqzy.flectonegrowth.common.service;

import lombok.RequiredArgsConstructor;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.config.CoreConfig;
import org.misqzy.flectonegrowth.common.domain.PlayerProfile;
import org.misqzy.flectonegrowth.common.domain.StoredPlayerData;
import org.misqzy.flectonegrowth.common.storage.Storage;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Assigns each player's target growth time. In fixed mode everyone shares
 * one value from config; in range mode each player is given a random value
 * on first join, persisted so it survives restarts/relogs.
 *
 * <p><b>Storage calls in here are blocking</b> - call only from an async
 * thread (see {@link Scheduler#runAsync}).</p>
 */
@RequiredArgsConstructor
public final class GrowthTimeAssigner {

    private final CoreConfig config;
    private final Storage storage;

    /** Loads (or assigns + persists) the target growth time into {@code profile}. Blocking - call off-thread. */
    public void loadInto(PlayerProfile profile) {
        loadInto(profile, null);
    }

    /**
     * Same, but takes the already-fetched row when the caller has one (see {@code Storage#loadStoredData}),
     * saving a round-trip. {@code null} means "not fetched": read it here. A {@code stored} whose own
     * {@code growthSeconds()} is null means "fetched, none persisted".
     */
    public void loadInto(PlayerProfile profile, StoredPlayerData stored) {
        if (!config.isRangeMode()) {
            profile.setTargetGrowthSeconds(config.growTimeSeconds());
            return;
        }

        UUID uuid = profile.uuid();
        Long persisted = stored != null ? stored.growthSeconds() : storage.getGrowthTimeSeconds(uuid);
        if (persisted != null) {
            profile.setTargetGrowthSeconds(persisted);
            return;
        }

        long assigned = randomSeconds();
        profile.setTargetGrowthSeconds(assigned);
        storage.setGrowthTimeSeconds(uuid, assigned);
    }

    private long randomSeconds() {
        long min = config.growTimeMinSeconds();
        long max = config.growTimeMaxSeconds();
        long range = max - min;
        long chosen = min + (range > 0 ? (long) ThreadLocalRandom.current().nextInt((int) Math.min(range + 1, Integer.MAX_VALUE)) : 0);
        return chosen;
    }
}
