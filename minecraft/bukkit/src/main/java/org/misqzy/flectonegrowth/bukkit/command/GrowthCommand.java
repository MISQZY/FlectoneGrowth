package org.misqzy.flectonegrowth.bukkit.command;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages.Line;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthPlugin;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.domain.Gender;
import org.misqzy.flectonegrowth.common.domain.OfflineProfile;
import org.misqzy.flectonegrowth.common.lang.TimeFormatter;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Read-only view of a player's current growth state, split into three
 * focused subcommands rather than one combined dump - {@code summary}
 * shows everything, {@code height}/{@code gender} show just that one
 * piece. Reuses the same {@code flectonegrowth.info}/{@code .others}
 * permissions across all three - they're all "view growth info", just
 * scoped differently, not separate capabilities.
 *
 * <p>The "others" variants accept a plain username rather than Cloud-bukkit's
 * online-only {@code Player} argument type, so they also work for a player
 * who's currently offline - see {@link #resolveTarget}. {@code HeightCommand}/
 * {@code GenderCommand} deliberately keep the online-only {@code Player}
 * argument; only this command's own "view" subcommands gained offline
 * support.</p>
 */
@RequiredArgsConstructor
public final class GrowthCommand {

    private final FlectoneGrowthPlugin plugin;
    private final FlectoneGrowthCore core;

    @Command("growth summary")
    @CommandDescription("View your own growth summary.")
    @Permission("flectonegrowth.info")
    public void summaryOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showSummary(player, onlineView(player)));
    }

    @Command("growth summary <target>")
    @CommandDescription("View another player's growth summary.")
    @Permission("flectonegrowth.info.others")
    public void summaryOthers(CommandSender sender, @Argument("target") String targetName) {
        resolveTarget(sender, targetName, this::showSummary);
    }

    @Command("growth height")
    @CommandDescription("View your own height info.")
    @Permission("flectonegrowth.info")
    public void heightOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showHeight(player, onlineView(player)));
    }

    @Command("growth height <target>")
    @CommandDescription("View another player's height info.")
    @Permission("flectonegrowth.info.others")
    public void heightOthers(CommandSender sender, @Argument("target") String targetName) {
        resolveTarget(sender, targetName, this::showHeight);
    }

    @Command("growth gender")
    @CommandDescription("View your own gender info.")
    @Permission("flectonegrowth.info")
    public void genderOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showGender(player, onlineView(player)));
    }

    @Command("growth gender <target>")
    @CommandDescription("View another player's gender info.")
    @Permission("flectonegrowth.info.others")
    public void genderOthers(CommandSender sender, @Argument("target") String targetName) {
        resolveTarget(sender, targetName, this::showGender);
    }

    /**
     * Resolves {@code targetName} to a {@link TargetView} and hands it to
     * {@code onResolved} - {@link Bukkit#getPlayerExact} first (exact match,
     * same as Cloud-bukkit's own {@code Player} argument parser would use),
     * falling back to an async, DB-backed lookup for a currently-offline
     * player. Mirrors {@code MigrateCommand}'s own online-check/async-fallback
     * shape (this project's existing precedent for hopping off/onto the main
     * thread from a Bukkit command).
     */
    private void resolveTarget(CommandSender sender, String targetName, BiConsumer<CommandSender, TargetView> onResolved) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            onResolved.accept(sender, onlineView(online));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GrowthEngine engine = core.growthEngine();
            UUID uuid = engine.resolveUuidByUsername(targetName);
            if (uuid == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        FlectoneGrowthMessages.send(core, sender, "growth-info.unknown-player", Map.of("player", targetName)));
                return;
            }

            OfflineProfile profile = engine.loadOffline(uuid);
            TargetView view = offlineView(targetName, profile);
            Bukkit.getScheduler().runTask(plugin, () -> onResolved.accept(sender, view));
        });
    }

    private void showSummary(CommandSender sender, TargetView view) {
        String locale = FlectoneGrowthMessages.resolveLocale(core, sender);
        List<Line> lines = new ArrayList<>();
        addHeader(lines, view);
        addHeight(lines, view, locale);
        addGender(lines, view, locale);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void showHeight(CommandSender sender, TargetView view) {
        String locale = FlectoneGrowthMessages.resolveLocale(core, sender);
        List<Line> lines = new ArrayList<>();
        addHeader(lines, view);
        addHeight(lines, view, locale);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void showGender(CommandSender sender, TargetView view) {
        String locale = FlectoneGrowthMessages.resolveLocale(core, sender);
        List<Line> lines = new ArrayList<>();
        addHeader(lines, view);
        addGender(lines, view, locale);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void addHeader(List<Line> lines, TargetView view) {
        lines.add(new Line("growth-info.header", Map.of("player", view.name())));
    }

    private void addGender(List<Line> lines, TargetView view, String locale) {
        lines.add(new Line("growth-info.gender", Map.of("gender", core.genderDisplayName(view.gender(), locale))));
    }

    /** Height value plus growth progress - progress is a height-growth metric, so it belongs with height, not the generic summary alone. */
    private void addHeight(List<Line> lines, TargetView view, String locale) {
        lines.add(new Line("growth-info.height", Map.of(
                "value", ScaleMath.formatValue(view.heightValue()),
                "unit", core.messages().heightUnit(locale),
                "gender", core.genderDisplayName(view.gender(), locale))));

        if (view.atMaxGrowth()) {
            lines.add(new Line("growth-info.max-reached"));
        } else {
            lines.add(new Line("growth-info.remaining", Map.of(
                    "time", TimeFormatter.format(view.secondsRemaining(), core.messages(), locale))));
        }
    }

    private TargetView onlineView(Player targetPlayer) {
        GrowthEngine engine = core.growthEngine();
        BukkitPlayerAdapter target = new BukkitPlayerAdapter(targetPlayer);
        return new TargetView(targetPlayer.getName(), engine.effectiveScale(target), engine.genderOf(target),
                engine.isAtMaxGrowth(target), engine.secondsUntilFullGrowth(target));
    }

    private TargetView offlineView(String name, OfflineProfile profile) {
        GrowthEngine engine = core.growthEngine();
        return new TargetView(name, engine.effectiveScale(profile), profile.gender(),
                engine.isAtMaxGrowth(profile), engine.secondsUntilFullGrowth(profile));
    }

    /** Uniform view over either a live online {@link Player} or a one-shot {@link OfflineProfile} - lets every {@code addXxx}/{@code showXxx} method above stay agnostic to which one it came from. {@code gender} stays a raw {@link Gender} rather than a pre-rendered display string, since that still needs to be resolved against the sender's own locale at render time (see {@link #addGender}). */
    private record TargetView(String name, double heightValue, Gender gender, boolean atMaxGrowth, long secondsRemaining) {}
}
