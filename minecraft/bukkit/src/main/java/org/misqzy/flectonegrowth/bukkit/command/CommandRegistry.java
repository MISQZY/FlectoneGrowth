package org.misqzy.flectonegrowth.bukkit.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.BukkitCommandManager;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthPlugin;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.domain.Gender;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires every command class through one {@link BukkitCommandManager}.
 *
 * <p>Targets plain {@link CommandSender} via cloud-bukkit's legacy command
 * manager rather than Paper's native Brigadier {@code CommandSourceStack} -
 * the only command-registration API that works on stock Spigot/CraftBukkit
 * as well as Paper/Purpur, so one code path covers every Bukkit-API server
 * instead of needing a second, Paper-only module.
 * {@code BukkitCommandManager}'s constructor is {@code protected}, so it's
 * instantiated through a trivial anonymous subclass, matching Cloud's own
 * documented usage pattern for this class.</p>
 *
 * <p>The {@code height-values} suggestion provider reads {@code core}'s
 * live min/max scale on every call rather than a snapshot taken at
 * registration time, so tab-completion automatically reflects the current
 * config after {@code /flectonegrowth reload} - no explicit "rebuild
 * suggestions" step is needed.</p>
 */
public final class CommandRegistry {

    private final BukkitCommandManager<CommandSender> manager;

    public CommandRegistry(FlectoneGrowthPlugin plugin, FlectoneGrowthCore core) {
        try {
            this.manager = new BukkitCommandManager<>(
                    plugin, ExecutionCoordinator.simpleCoordinator(), SenderMapper.identity()) {};
        } catch (BukkitCommandManager.InitializationException e) {
            throw new IllegalStateException("Failed to initialise the Bukkit command manager", e);
        }

        manager.parserRegistry().registerSuggestionProvider("height-values",
                SuggestionProvider.blockingStrings((context, input) -> heightSuggestions(core)));
        manager.parserRegistry().registerSuggestionProvider("gender-types",
                SuggestionProvider.blockingStrings((context, input) -> genderSuggestions(core)));

        manager.exceptionController().registerHandler(InvalidSyntaxException.class, ctx -> {
            CommandSender sender = ctx.context().sender();
            // HelpCommand's hand-maintained list, not Cloud's own auto-generated
            // correctSyntax() - that walks the whole branching command tree from
            // the failure point and can mash multiple literal/argument
            // alternatives into one confusing string. Falls back to pointing at
            // /flectonegrowth help when the matched literal chain doesn't
            // uniquely identify one entry, rather than guessing.
            Component syntax = HelpCommand.syntaxFor(sender, ctx.exception().currentChain())
                    .orElseGet(() -> Component.text("/flectonegrowth help")
                            .clickEvent(ClickEvent.suggestCommand("/flectonegrowth help")));
            FlectoneGrowthMessages.send(core, sender, "command.invalid-syntax", Map.of("syntax", syntax));
        });

        AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);
        parser.parse(new HeightCommand(core));
        parser.parse(new GenderCommand(core));
        parser.parse(new GrowthCommand(core));
        parser.parse(new PluginAdminCommand(plugin));
        parser.parse(new HelpCommand(core));
    }

    private static List<String> heightSuggestions(FlectoneGrowthCore core) {
        GrowthEngine engine = core.growthEngine();
        double min = engine.minScale();
        double max = core.config().maxScale();

        List<String> values = new ArrayList<>();
        double step = (max - min) / 4.0;
        for (int i = 0; i <= 4; i++) {
            values.add(ScaleMath.formatValue(min + step * i));
        }
        return values;
    }

    private static List<String> genderSuggestions(FlectoneGrowthCore core) {
        List<String> values = new ArrayList<>();
        for (Gender gender : core.genderRegistry().all()) {
            values.add(gender.key());
        }
        return values;
    }
}
