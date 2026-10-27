package org.misqzy.flectonegrowth.common.domain;

/**
 * Everything persisted about one player that a join (or an offline lookup)
 * needs, fetched in a single storage round-trip - see
 * {@code Storage#loadStoredData}. Each field is {@code null} when no
 * (valid) row exists for that player.
 */
public record StoredPlayerData(Double customScale, String genderKey, Long growthSeconds, PlayTime playTime) {

    public static final StoredPlayerData EMPTY = new StoredPlayerData(null, null, null, null);
}
