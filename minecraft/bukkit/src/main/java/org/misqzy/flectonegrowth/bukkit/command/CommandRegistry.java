package org.misqzy.flectonegrowth.bukkit.command;

import org.bukkit.command.CommandSender;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.BukkitCommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.exception.CommandExecutionException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
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
import java.util.logging.Level;
import java.util.stream.Collectors;

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

    /** Number of evenly-spaced height suggestions offered between min and max scale (min, max, and this many steps in between). */
    private static final int HEIGHT_SUGGESTION_STEPS = 4;

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

        // Cloud's default StandardCommandSyntaxFormatter spells out every sibling
        // literal at a branching node (e.g. "fg reload|migrate playtime", "growth
        // summary|height|gender [target]") - correct, but exposes implementation
        // detail (subcommand names, including admin-only ones a sender may not even
        // have permission for) a player mistyping a command doesn't need. Collapsing
        // it to a generic "/<already-typed literals> <type> [args]" keeps the hint
        // useful (still shows the literal chain typed correctly so far) without
        // enumerating options.
        //
        // Only LITERAL knownComponents are kept in that prefix - a successfully
        // parsed argument component (e.g. height's own value argument, itself named
        // "type" to match this generic wording) would otherwise get echoed by name
        // right next to the generic "<type>" suffix below, producing a confusing
        // "height type <type> [args]" for something as simple as trailing garbage
        // after an otherwise-valid "/height 1.85 x".
        //
        // The suffix is always appended, regardless of whether the failure node is
        // a leaf - Cloud also reaches a leaf node (one with no further children) for
        // that same trailing-garbage case, which still needs a hint rather than
        // silently falling back to just the bare prefix with no hint at all.
        manager.commandSyntaxFormatter((sender, knownComponents, node) -> {
            String known = knownComponents.stream()
                    .filter(component -> component.type() == CommandComponent.ComponentType.LITERAL)
                    .map(CommandComponent::name)
                    .collect(Collectors.joining(" "));
            return "/" + (known.isEmpty() ? "" : known + " ") + "<type> [args]";
        });

        manager.exceptionController().registerHandler(InvalidSyntaxException.class, ctx -> {
            CommandSender sender = ctx.context().sender();
            FlectoneGrowthMessages.send(core, sender, "command.invalid-syntax",
                    Map.of("syntax", ctx.exception().correctSyntax()));
        });
        // Without this, a sender lacking the command's @Permission got no feedback at all -
        // Cloud's ExecutionCoordinator has no built-in default for this exception type either.
        manager.exceptionController().registerHandler(NoPermissionException.class, ctx ->
                FlectoneGrowthMessages.send(core, ctx.context().sender(), "command.no-permission"));
        // A bug inside a command handler (an unchecked exception thrown while executing, as
        // opposed to a parse-time failure) would otherwise also vanish silently from the
        // sender's perspective - only ever visible as a stack trace on the console, if that.
        // Logging it here too means it's not solely dependent on whoever's watching the console
        // at that moment to ever notice a command is broken.
        manager.exceptionController().registerHandler(CommandExecutionException.class, ctx -> {
            plugin.getLogger().log(Level.SEVERE,
                    "Unhandled exception executing a FlectoneGrowth command", ctx.exception().getCause());
            FlectoneGrowthMessages.send(core, ctx.context().sender(), "command.error");
        });

        AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);
        parser.parse(new HeightCommand(core));
        parser.parse(new GenderCommand(core));
        parser.parse(new GrowthCommand(core));
        parser.parse(new PluginAdminCommand(plugin));
        parser.parse(new MigrateCommand(plugin, core));
    }

    private static List<String> heightSuggestions(FlectoneGrowthCore core) {
        GrowthEngine engine = core.growthEngine();
        double min = engine.minScale();
        double max = core.config().maxScale();

        List<String> values = new ArrayList<>();
        double step = (max - min) / HEIGHT_SUGGESTION_STEPS;
        for (int i = 0; i <= HEIGHT_SUGGESTION_STEPS; i++) {
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
