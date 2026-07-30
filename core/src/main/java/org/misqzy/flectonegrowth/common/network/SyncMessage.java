package org.misqzy.flectonegrowth.common.network;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.UUID;

/**
 * A cross-server cache-invalidation event. The plugin's source of truth is
 * always the shared database (MySQL/MariaDB) configured identically on
 * every backend server; this message only tells sibling servers "re-apply
 * this player's state now" instead of waiting for their next natural
 * read - it is a latency optimisation, not a replacement for the DB.
 */
@Getter
@Accessors(fluent = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class SyncMessage {

    public enum Type { SCALE_SET, SCALE_REMOVED, GENDER_SET }

    private final Type type;
    private final UUID playerUuid;
    private final String stringValue;
    private final double doubleValue;
    private final String sourceServerId;

    public static SyncMessage scaleSet(UUID uuid, double scale, String sourceServerId) {
        return new SyncMessage(Type.SCALE_SET, uuid, null, scale, sourceServerId);
    }

    public static SyncMessage scaleRemoved(UUID uuid, String sourceServerId) {
        return new SyncMessage(Type.SCALE_REMOVED, uuid, null, 0, sourceServerId);
    }

    public static SyncMessage genderSet(UUID uuid, String genderKey, String sourceServerId) {
        return new SyncMessage(Type.GENDER_SET, uuid, genderKey, 0, sourceServerId);
    }

    static SyncMessage raw(Type type, UUID uuid, String stringValue, double doubleValue, String sourceServerId) {
        return new SyncMessage(type, uuid, stringValue, doubleValue, sourceServerId);
    }
}
