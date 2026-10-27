package org.misqzy.flectonegrowth.common.service;

import lombok.RequiredArgsConstructor;
import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.misqzy.flectonegrowth.common.domain.PlayerProfile;
import org.misqzy.flectonegrowth.common.storage.Storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Loads/persists each player's playtime record - modeled on FlectonePulse's
 * own scheme (see {@code ARCHITECTURE.md} "Playtime tracking"): a frozen
 * {@code total} checkpointed at join/quit, reconstructed live as
 * {@code total + (now - last)} while online rather than written continuously.
 *
 * <p><b>Storage calls in here are blocking</b> - call only from an async
 * thread, same contract as {@link GrowthTimeAssigner}.</p>
 */
@RequiredArgsConstructor
public final class PlayTimeTracker {

    private final Storage storage;

    /** Pure read - hydrates {@code profile} from whatever's already persisted under {@code server}, without touching sessions/last. Safe to call any time, e.g. a reload-refresh of an already-online player. */
    public void loadInto(PlayerProfile profile, String server) {
        PlayTime pt = storage.getPlayTime(profile.uuid(), server);
        if (pt != null) profile.setPlayTime(pt.total(), pt.last(), pt.first(), pt.sessions());
    }

    /** Upserts the DB row only (no re-read): for callers that already hold the previous row and can derive the new one - see {@code GrowthEngine#loadPlayer}. Returns whether the write succeeded. */
    public boolean recordJoin(UUID uuid, String server, long now) {
        return storage.recordJoin(uuid, server, now);
    }

    /** Only for a genuine join: upserts the DB row for {@code server} (bumping sessions/last, or creating first=last=now/total=0/sessions=1 on a brand new player), then hydrates {@code profile} from the result. */
    public void recordJoin(PlayerProfile profile, String server) {
        long now = Instant.now().getEpochSecond();
        storage.recordJoin(profile.uuid(), server, now);
        loadInto(profile, server);
    }

    /** Checkpoints accumulated total at quit. */
    public void checkpoint(UUID uuid, String server, long total, long last) {
        storage.checkpointPlayTime(uuid, server, total, last);
    }
}
