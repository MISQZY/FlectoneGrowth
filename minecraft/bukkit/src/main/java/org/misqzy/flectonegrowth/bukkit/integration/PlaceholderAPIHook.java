package org.misqzy.flectonegrowth.bukkit.integration;

import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.domain.OfflineProfile;
import org.misqzy.flectonegrowth.common.lang.TimeFormatter;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;
import org.misqzy.flectonegrowth.bukkit.BuildVersion;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Exposes {@code %flectonegrowth_<placeholder>%} to other plugins. Swapped
 * from {@code FlectoneGrowthService} to {@link GrowthEngine} + a fresh
 * {@link BukkitPlayerAdapter} per call, same pattern every command class
 * uses - unchanged in spirit from the original hook.
 *
 * <p>{@link #getPlaceholders()} lists every placeholder below so
 * {@code /papi info flectonegrowth} documents them instead of showing an
 * empty "Placeholders" section.</p>
 */
@RequiredArgsConstructor
public final class PlaceholderAPIHook extends PlaceholderExpansion {

    private static final List<String> PLACEHOLDERS = List.of(
            "%flectonegrowth_height%",
            "%flectonegrowth_height_full%",
            "%flectonegrowth_height_min%",
            "%flectonegrowth_height_max%",
            "%flectonegrowth_scale%",
            "%flectonegrowth_scale_min%",
            "%flectonegrowth_scale_max%",
            "%flectonegrowth_gender%",
            "%flectonegrowth_growth_remaining_seconds%",
            "%flectonegrowth_growth_remaining_formatted%",
            "%flectonegrowth_growth_percentage%",
            "%flectonegrowth_growth_max_reached%",
            "%flectonegrowth_growth_active%",
            "%flectonegrowth_has_custom_scale%"
    );

    private static final Set<String> PLACEHOLDER_KEYS = PLACEHOLDERS.stream()
            .map(p -> p.substring("%flectonegrowth_".length(), p.length() - 1))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * PAPI's placeholder API has no async contract - {@link #onRequest} must
     * return a value immediately, and is often called on the main thread. So
     * {@link #resolveOffline} never touches storage itself: a fresh cache hit
     * is served as-is, a stale one is served as-is too (last-known value)
     * while one deduplicated async refresh runs, and a cold miss returns an
     * empty string while the first load runs - the next request (PAPI
     * consumers re-resolve every refresh) then gets real data.
     */
    private static final long OFFLINE_CACHE_TTL_MILLIS = 3_000L;
    /** A stale entry is only kept (and served while refreshing) this long; beyond it, it is evicted by the sweep. */
    private static final long OFFLINE_CACHE_MAX_AGE_MILLIS = 10 * 60_000L;
    private static final int OFFLINE_CACHE_MAX_ENTRIES = 2_048;
    private static final long OFFLINE_CACHE_SWEEP_EVERY = 200L;

    private final FlectoneGrowthCore core;
    private final Scheduler scheduler;
    private final Map<UUID, CachedOfflineProfile> offlineCache = new ConcurrentHashMap<>();
    private final Set<UUID> offlineLoadsInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong offlineCacheWritesSinceSweep = new AtomicLong();

    private record CachedOfflineProfile(OfflineProfile profile, long fetchedAtMillis) {}

    @Override public @NotNull String getIdentifier() { return "flectonegrowth"; }
    @Override public @NotNull String getName() { return "FlectoneGrowth"; }
    @Override public @NotNull String getAuthor() { return "MISQZY"; }
    @Override public @NotNull String getVersion() { return BuildVersion.VERSION; }
    @Override public boolean persist() { return true; }
    @Override public @NotNull List<String> getPlaceholders() { return PLACEHOLDERS; }

    @Override
    public String onRequest(@NotNull OfflinePlayer offlinePlayer, @NotNull String params) {
        GrowthEngine engine = core.growthEngine();

        if (offlinePlayer instanceof Player player && player.isOnline()) {
            String locale = FlectoneGrowthMessages.resolveLocale(core, player);
            return resolveOnline(engine, new BukkitPlayerAdapter(player), params, locale);
        }
        // The subject has no connection of their own to read a client locale from - PAPI
        // gives no separate "viewer" to fall back to either, so this renders in
        // config.yml's configured default, same as every other no-recipient case.
        return resolveOffline(engine, offlinePlayer, params, null);
    }

    private String resolveOnline(GrowthEngine engine, BukkitPlayerAdapter target, String params, String locale) {
        return resolve(params, new GrowthData(
                () -> engine.effectiveScale(target),
                engine::minScale,
                () -> engine.maxScaleFor(target),
                () -> core.genderDisplayName(target, locale),
                () -> engine.secondsUntilFullGrowth(target),
                () -> engine.growthProgress(target),
                () -> engine.isAtMaxGrowth(target),
                () -> engine.hasCustomScale(target)
        ), locale);
    }

    /**
     * Same placeholders, computed from a one-shot {@link OfflineProfile}
     * read straight out of storage instead of the online-only
     * {@code ProfileCache}/live entity attribute - see
     * {@link GrowthEngine#loadOffline} for the (deliberately blocking)
     * tradeoff. Lets leaderboard/hologram plugins showing offline players
     * get real data instead of PAPI's usual empty-string default.
     */
    private String resolveOffline(GrowthEngine engine, OfflinePlayer offlinePlayer, String params, String locale) {
        // Unknown keys keep returning null without ever triggering a load.
        if (!PLACEHOLDER_KEYS.contains(params.toLowerCase(Locale.ROOT))) return null;

        OfflineProfile profile = cachedOfflineProfile(engine, offlinePlayer.getUniqueId());
        if (profile == null) return ""; // first load still running - see the field javadoc above
        return resolve(params, new GrowthData(
                () -> engine.effectiveScale(profile),
                engine::minScale,
                () -> engine.maxScaleFor(profile.gender()),
                () -> core.genderDisplayName(profile.gender(), locale),
                () -> engine.secondsUntilFullGrowth(profile),
                () -> engine.growthProgress(profile),
                () -> engine.isAtMaxGrowth(profile),
                profile::hasCustomScale
        ), locale);
    }

    /** Pure cache read; schedules at most one async refresh per uuid when missing or stale. {@code null} on a cold miss. */
    private OfflineProfile cachedOfflineProfile(GrowthEngine engine, UUID uuid) {
        long now = System.currentTimeMillis();
        CachedOfflineProfile cached = offlineCache.get(uuid);
        if (cached != null && now - cached.fetchedAtMillis() < OFFLINE_CACHE_TTL_MILLIS) {
            return cached.profile();
        }
        refreshOfflineAsync(engine, uuid);
        return cached != null ? cached.profile() : null;
    }

    private void refreshOfflineAsync(GrowthEngine engine, UUID uuid) {
        if (!offlineLoadsInFlight.add(uuid)) return;
        try {
            scheduler.runAsync(() -> {
                try {
                    OfflineProfile profile = engine.loadOffline(uuid);
                    long now = System.currentTimeMillis();
                    offlineCache.put(uuid, new CachedOfflineProfile(profile, now));
                    sweepIfDue(now);
                } finally {
                    offlineLoadsInFlight.remove(uuid);
                }
            });
        } catch (RuntimeException e) { // e.g. plugin disabled mid-call
            offlineLoadsInFlight.remove(uuid);
        }
    }

    /** Every {@link #OFFLINE_CACHE_SWEEP_EVERY} writes (or whenever over the size cap), evicts entries too old to be worth serving; a still-oversized cache is cleared outright (it is only a cache). */
    private void sweepIfDue(long now) {
        if (offlineCacheWritesSinceSweep.incrementAndGet() < OFFLINE_CACHE_SWEEP_EVERY
                && offlineCache.size() <= OFFLINE_CACHE_MAX_ENTRIES) return;
        offlineCacheWritesSinceSweep.set(0L);
        offlineCache.entrySet().removeIf(e -> now - e.getValue().fetchedAtMillis() >= OFFLINE_CACHE_MAX_AGE_MILLIS);
        if (offlineCache.size() > OFFLINE_CACHE_MAX_ENTRIES) offlineCache.clear();
    }

    /**
     * The one placeholder-key-to-value mapping both {@link #resolveOnline}
     * and {@link #resolveOffline} drive - previously typed out as two
     * identical 14-case switches (one per data source), which meant a new
     * placeholder had to be remembered in both places. Each {@link GrowthData}
     * field stays lazy (a supplier, not a precomputed value) so a request for
     * a single placeholder (PAPI's normal usage - one {@code onRequest} call
     * per placeholder) still only evaluates the one figure it actually needs.
     */
    private String resolve(String params, GrowthData data, String locale) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "height" -> ScaleMath.formatValue(data.current().getAsDouble());
            case "height_full" -> ScaleMath.format(data.current().getAsDouble(), core.messages().heightUnit(locale));
            case "height_min" -> ScaleMath.formatValue(data.min().getAsDouble());
            case "height_max" -> ScaleMath.formatValue(data.max().getAsDouble());
            case "scale" -> ScaleMath.formatRaw(data.current().getAsDouble());
            case "scale_min" -> ScaleMath.formatRaw(data.min().getAsDouble());
            case "scale_max" -> ScaleMath.formatRaw(data.max().getAsDouble());
            case "gender" -> data.genderDisplayName().get();
            case "growth_remaining_seconds" -> String.valueOf(data.secondsRemaining().getAsLong());
            case "growth_remaining_formatted" -> TimeFormatter.format(data.secondsRemaining().getAsLong(), core.messages(), locale);
            case "growth_percentage" -> ScaleMath.formatPercentage(data.progress().getAsDouble());
            case "growth_max_reached" -> String.valueOf(data.atMax().getAsBoolean());
            case "growth_active" -> String.valueOf(!data.atMax().getAsBoolean() && !data.hasCustomScale().getAsBoolean());
            case "has_custom_scale" -> String.valueOf(data.hasCustomScale().getAsBoolean());
            default -> null;
        };
    }

    /** Lazily-evaluated view over either a live {@link BukkitPlayerAdapter} or a one-shot {@link OfflineProfile} - see {@link #resolve}. */
    private record GrowthData(
            DoubleSupplier current,
            DoubleSupplier min,
            DoubleSupplier max,
            Supplier<String> genderDisplayName,
            LongSupplier secondsRemaining,
            DoubleSupplier progress,
            BooleanSupplier atMax,
            BooleanSupplier hasCustomScale
    ) {}
}
