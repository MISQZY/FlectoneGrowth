package org.misqzy.flectonegrowth.bukkit.command;

import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.misqzy.flectonegrowth.bukkit.BukkitPlayerAdapter;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages.Line;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.lang.TimeFormatter;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of a player's current growth state, split into three
 * focused subcommands rather than one combined dump - {@code summary}
 * shows everything, {@code height}/{@code gender} show just that one
 * piece. Reuses the same {@code flectonegrowth.info}/{@code .others}
 * permissions across all three - they're all "view growth info", just
 * scoped differently, not separate capabilities.
 */
@RequiredArgsConstructor
public final class GrowthCommand {

    private final FlectoneGrowthCore core;

    @Command("growth summary")
    @CommandDescription("View your own growth summary.")
    @Permission("flectonegrowth.info")
    public void summaryOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showSummary(player, player));
    }

    @Command("growth summary <target>")
    @CommandDescription("View another player's growth summary.")
    @Permission("flectonegrowth.info.others")
    public void summaryOthers(CommandSender sender, @Argument("target") Player target) {
        showSummary(sender, target);
    }

    @Command("growth height")
    @CommandDescription("View your own height info.")
    @Permission("flectonegrowth.info")
    public void heightOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showHeight(player, player));
    }

    @Command("growth height <target>")
    @CommandDescription("View another player's height info.")
    @Permission("flectonegrowth.info.others")
    public void heightOthers(CommandSender sender, @Argument("target") Player target) {
        showHeight(sender, target);
    }

    @Command("growth gender")
    @CommandDescription("View your own gender info.")
    @Permission("flectonegrowth.info")
    public void genderOwn(CommandSender sender) {
        CommandGuards.requirePlayer(core, sender, player -> showGender(player, player));
    }

    @Command("growth gender <target>")
    @CommandDescription("View another player's gender info.")
    @Permission("flectonegrowth.info.others")
    public void genderOthers(CommandSender sender, @Argument("target") Player target) {
        showGender(sender, target);
    }

    private void showSummary(CommandSender sender, Player targetPlayer) {
        List<Line> lines = new ArrayList<>();
        addHeader(lines, targetPlayer);
        addHeight(lines, targetPlayer);
        addGender(lines, targetPlayer);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void showHeight(CommandSender sender, Player targetPlayer) {
        List<Line> lines = new ArrayList<>();
        addHeader(lines, targetPlayer);
        addHeight(lines, targetPlayer);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void showGender(CommandSender sender, Player targetPlayer) {
        List<Line> lines = new ArrayList<>();
        addHeader(lines, targetPlayer);
        addGender(lines, targetPlayer);
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    private void addHeader(List<Line> lines, Player targetPlayer) {
        lines.add(new Line("growth-info.header", Map.of("player", targetPlayer.getName())));
    }

    private void addGender(List<Line> lines, Player targetPlayer) {
        BukkitPlayerAdapter target = new BukkitPlayerAdapter(targetPlayer);
        lines.add(new Line("growth-info.gender", Map.of("gender", core.genderDisplayName(target))));
    }

    /** Height value plus growth progress - progress is a height-growth metric, so it belongs with height, not the generic summary alone. */
    private void addHeight(List<Line> lines, Player targetPlayer) {
        GrowthEngine engine = core.growthEngine();
        BukkitPlayerAdapter target = new BukkitPlayerAdapter(targetPlayer);

        double current = engine.effectiveScale(target);

        lines.add(new Line("growth-info.height", Map.of(
                "value", ScaleMath.formatValue(current),
                "unit", core.messages().heightUnit(),
                "gender", core.genderDisplayName(target))));

        if (engine.isAtMaxGrowth(target)) {
            lines.add(new Line("growth-info.max-reached"));
        } else {
            long remaining = engine.secondsUntilFullGrowth(target);
            lines.add(new Line("growth-info.remaining", Map.of(
                    "time", TimeFormatter.format(remaining, core.messages()))));
        }
    }
}
