package org.misqzy.flectonegrowth.bukkit.api;

import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;

/**
 * Default {@link FlectoneGrowthAPI} implementation, backed by the live
 * {@link FlectoneGrowthCore}. Public only so {@code FlectoneGrowthPlugin} (a
 * different package) can construct and register it - other plugins should
 * only ever reference it through the {@link FlectoneGrowthAPI} interface.
 */
@RequiredArgsConstructor
public final class FlectoneGrowthAPIImpl implements FlectoneGrowthAPI {

    private final FlectoneGrowthCore core;

    @Override
    public Optional<Double> getHeight(Player player) {
        return query(player, (engine, target) -> ScaleMath.toMeters(engine.effectiveScale(target)));
    }

    @Override
    public Optional<Double> getScale(Player player) {
        return query(player, GrowthEngine::effectiveScale);
    }

    @Override
    public double getMinHeight() {
        return ScaleMath.toMeters(core.growthEngine().minScale());
    }

    @Override
    public Optional<Double> getMaxHeight(Player player) {
        return query(player, (engine, target) -> ScaleMath.toMeters(engine.maxScaleFor(target)));
    }

    @Override
    public Optional<String> getGender(Player player) {
        return query(player, (engine, target) -> engine.genderOf(target).key());
    }

    @Override
    public Optional<String> getGenderDisplayName(Player player) {
        return query(player, (engine, target) -> core.genderDisplayName(target));
    }

    @Override
    public Optional<Double> getGrowthProgress(Player player) {
        return query(player, GrowthEngine::growthProgress);
    }

    @Override
    public OptionalLong getGrowthRemainingSeconds(Player player) {
        if (unavailable(player)) return OptionalLong.empty();
        return OptionalLong.of(core.growthEngine().secondsUntilFullGrowth(new BukkitPlayerAdapter(player)));
    }

    @Override
    public Optional<Boolean> isAtMaxGrowth(Player player) {
        return query(player, GrowthEngine::isAtMaxGrowth);
    }

    @Override
    public Optional<Boolean> hasCustomScale(Player player) {
        return query(player, GrowthEngine::hasCustomScale);
    }

    private <T> Optional<T> query(Player player, BiFunction<GrowthEngine, BukkitPlayerAdapter, T> function) {
        if (unavailable(player)) return Optional.empty();
        return Optional.of(function.apply(core.growthEngine(), new BukkitPlayerAdapter(player)));
    }

    private static boolean unavailable(Player player) {
        return player == null || !player.isOnline();
    }
}
