package org.misqzy.flectonegrowth.common.domain;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * A configurable gender variant. Each gender has its own maximum growth
 * scale, so networks can give e.g. "female" a lower max height than "male"
 * (or add entirely custom, non-binary variants) purely through config.
 *
 * <p>The constructor lower-cases {@code key} (not a raw passthrough) so it
 * stays hand-written; {@code equals}/{@code hashCode} (key-only identity)
 * and {@code toString} (bare key, not Lombok's default {@code Gender(key=...)}
 * format) aren't a Lombok-generated shape either and stay hand-written too.</p>
 */
@Getter
@Accessors(fluent = true)
public final class Gender {

    public static final String DEFAULT_KEY = "male";

    private final String key;
    private final double maxScale;

    public Gender(String key, double maxScale) {
        this.key = key.toLowerCase();
        this.maxScale = maxScale;
    }

    @Override
    public String toString() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof Gender g && key.equals(g.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
