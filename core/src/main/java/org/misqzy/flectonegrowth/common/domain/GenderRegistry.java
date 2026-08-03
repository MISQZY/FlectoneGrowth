package org.misqzy.flectonegrowth.common.domain;

import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Parses and resolves the configured gender types. Platform-agnostic: it
 * only depends on {@link ConfigView}, never on a Bukkit/Velocity config type.
 */
public final class GenderRegistry {

    /** Fallback "female" max-scale factor (relative to {@code globalMaxScale}) used only when no {@code types} section is configured at all. */
    private static final double DEFAULT_FEMALE_SCALE_FACTOR = 0.9;

    private final boolean enabled;
    private final double globalMaxScale;
    private final Map<String, Gender> byKey = new LinkedHashMap<>();
    private final Map<String, Gender> byAlias = new HashMap<>();
    private final Gender defaultGender;

    public GenderRegistry(ConfigView genderConfig, double globalMaxScale) {
        this.globalMaxScale = globalMaxScale;
        this.enabled = genderConfig.getBoolean("enabled", true);

        String defaultKey = genderConfig.getString("default", Gender.DEFAULT_KEY).toLowerCase(Locale.ROOT);

        ConfigView types = genderConfig.getSection("types");
        if (types != null) {
            for (String key : types.keys()) {
                ConfigView entry = types.getSection(key);
                if (entry == null) continue;

                double maxScale = entry.getDouble("max-scale", globalMaxScale);
                Gender gender = new Gender(key, maxScale);
                byKey.put(key.toLowerCase(Locale.ROOT), gender);
                byAlias.put(key.toLowerCase(Locale.ROOT), gender);
                for (String alias : entry.getStringList("aliases")) {
                    byAlias.put(alias.toLowerCase(Locale.ROOT), gender);
                }
            }
        }

        if (byKey.isEmpty()) {
            register(new Gender("male", globalMaxScale), List.of("m"));
            register(new Gender("female", globalMaxScale * DEFAULT_FEMALE_SCALE_FACTOR), List.of("f"));
        }

        Gender resolved = byKey.get(defaultKey);
        defaultGender = resolved != null ? resolved : byKey.values().iterator().next();
    }

    private void register(Gender gender, List<String> aliases) {
        byKey.put(gender.key(), gender);
        byAlias.put(gender.key(), gender);
        for (String alias : aliases) byAlias.put(alias.toLowerCase(Locale.ROOT), gender);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Gender getDefault() {
        return defaultGender;
    }

    public Collection<Gender> all() {
        return byKey.values();
    }

    public Gender resolve(String input) {
        if (input == null) return defaultGender;
        Gender g = byAlias.get(input.trim().toLowerCase(Locale.ROOT));
        return g != null ? g : defaultGender;
    }

    public double getMaxScaleFor(Gender gender) {
        if (!enabled || gender == null) return globalMaxScale;
        return gender.maxScale();
    }

    public boolean isGenderInput(String value) {
        return value != null && byAlias.containsKey(value.trim().toLowerCase(Locale.ROOT));
    }

    /** @param messageLookup a lookup that returns {@code null} for an undefined key (e.g. {@code Messages::rawOrNull}), not a visible placeholder string - otherwise that placeholder would render as-is instead of falling back to {@code gender.key()}. */
    public String resolveDisplayName(Gender gender, Function<String, String> messageLookup) {
        if (gender == null) return defaultGender.key();
        String raw = messageLookup.apply("gender.display." + gender.key());
        return raw != null ? raw : gender.key();
    }
}
