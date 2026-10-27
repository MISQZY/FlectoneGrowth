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
import org.misqzy.flectonegrowth.common.lang.Messages;
import org.misqzy.flectonegrowth.common.service.GrowthEngine;
import org.misqzy.flectonegrowth.common.service.ScaleMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** The constant, argument-describing tail commandSyntaxFormatter below always appends after the literal chain - shared with the InvalidSyntaxException handler so it can strip exactly this back off {@code correctSyntax()}'s result to recover just the literal chain (see that handler for why). */
    private static final String SYNTAX_ARGUMENT_SUFFIX = "<type> [args]";

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
            String known = literalPrefix(knownComponents);
            return "/" + (known.isEmpty() ? "" : known + " ") + SYNTAX_ARGUMENT_SUFFIX;
        });

        manager.exceptionController().registerHandler(InvalidSyntaxException.class, ctx -> {
            CommandSender sender = ctx.context().sender();
            // "usage" is the full hint ("/growth summary <type> [args]") shown on the visible
            // line and on hover. "command" is just the literal chain it was built from, with a
            // trailing space ("/growth summary ") - what suggest_command actually inserts, so
            // clicking leaves the sender ready to type their own argument instead of also
            // having to delete a literal "<type> [args]" first.
            //
            // Derived by stripping SYNTAX_ARGUMENT_SUFFIX back off "usage" itself, not by
            // recomputing independently from ctx.exception().currentChain() - that list can
            // legitimately differ from what commandSyntaxFormatter above actually used to build
            // "usage" (e.g. at a branching node with no literal committed yet, currentChain() can
            // still surface one specific candidate child's components, which isn't the same thing
            // as "the sender typed this"). Stripping the known, constant suffix off the one string
            // the formatter actually produced is the only way to guarantee the two stay in sync.
            String syntax = ctx.exception().correctSyntax();
            String command = syntax.endsWith(SYNTAX_ARGUMENT_SUFFIX)
                    ? syntax.substring(0, syntax.length() - SYNTAX_ARGUMENT_SUFFIX.length())
                    : syntax;
            // <click:suggest_command:'...'> in command.invalid-syntax's own localization
            // template needs "command" inlined as literal text before parsing (Messages.
            // TemplateText) - a plain placeholder can never resolve inside another tag's own
            // quoted argument (see that record's javadoc). "usage" has no such requirement -
            // it only ever appears in ordinary content, so a normal placeholder is enough.
            FlectoneGrowthMessages.send(core, sender, "command.invalid-syntax", Map.of(
                    "usage", syntax,
                    "command", new Messages.TemplateText(command)));
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
            org.misqzy.flectonegrowth.common.log.PluginLog.error(plugin.getLogger(), "Command execution",
                    ctx.exception().getCause() != null ? ctx.exception().getCause() : ctx.exception());
            FlectoneGrowthMessages.send(core, ctx.context().sender(), "command.error");
        });

        AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);
        parser.parse(new HeightCommand(core));
        parser.parse(new GenderCommand(core));
        parser.parse(new GrowthCommand(plugin, core));
        parser.parse(new PluginAdminCommand(plugin));
        parser.parse(new MigrateCommand(plugin, core));
    }

    /** Every LITERAL component's name, space-joined - the part of a command chain that's never a value a sender types (a subcommand name), used both for the generic syntax hint and for the click-to-insert target (see the two call sites above). */
    private static String literalPrefix(List<? extends CommandComponent<?>> components) {
        return components.stream()
                .filter(component -> component.type() == CommandComponent.ComponentType.LITERAL)
                .map(CommandComponent::name)
                .collect(Collectors.joining(" "));
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
