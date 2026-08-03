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
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.domain.Gender;
import org.misqzy.flectonegrowth.common.domain.GenderRegistry;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;

import java.util.Map;

/**
 * Sets a player's gender, which controls their maximum growth scale.
 *
 * <p>The "others" variant lives under a literal {@code set} prefix rather
 * than {@code gender <target> <type>} directly - see the equivalent note on
 * {@link HeightCommand}; a {@code target} (Player) argument and a
 * {@code type} (String) argument can't both sit directly under
 * {@code gender} as sibling argument nodes.</p>
 */
@RequiredArgsConstructor
public final class GenderCommand {

    private final FlectoneGrowthCore core;

    @Command("gender <type>")
    @CommandDescription("Set your own gender.")
    @Permission("flectonegrowth.gender")
    public void setOwn(CommandSender sender, @Argument(value = "type", suggestions = "gender-types") String type) {
        CommandGuards.requirePlayer(core, sender, player -> apply(player, player, type));
    }

    @Command("gender set <target> <type>")
    @CommandDescription("Set another player's gender.")
    @Permission("flectonegrowth.gender.others")
    public void setOthers(CommandSender sender, @Argument("target") Player target,
                           @Argument(value = "type", suggestions = "gender-types") String type) {
        apply(sender, target, type);
    }

    private void apply(CommandSender sender, Player targetPlayer, String type) {
        GenderRegistry registry = core.genderRegistry();
        if (!registry.isEnabled()) {
            FlectoneGrowthMessages.send(core, sender, "gender.disabled");
            return;
        }
        if (!registry.isGenderInput(type)) {
            FlectoneGrowthMessages.send(core, sender, "gender.unknown", Map.of("input", type));
            return;
        }

        Gender gender = registry.resolve(type);
        GrowthEngine engine = core.growthEngine();
        BukkitPlayerAdapter target = new BukkitPlayerAdapter(targetPlayer);
        String locale = FlectoneGrowthMessages.resolveLocale(core, sender);
        engine.setGender(target, gender,
                () -> FlectoneGrowthMessages.send(core, sender, "gender.set", Map.of(
                        "player", targetPlayer.getName(),
                        "gender", core.genderDisplayName(gender, locale))),
                () -> FlectoneGrowthMessages.send(core, sender, "gender.set-failed"));
    }
}
