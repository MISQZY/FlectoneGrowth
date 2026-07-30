package org.misqzy.flectonegrowth.bukkit.command;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.component.CommandComponent;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages.Line;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lists every FlectoneGrowth command the sender has permission to use, and
 * (see {@link #syntaxFor}) resolves a specific command's clean syntax for
 * {@code CommandRegistry}'s invalid-syntax handler - one hand-maintained
 * source of truth for "what does this command actually look like", instead
 * of Cloud's own auto-generated {@code correctSyntax()}. That walks the
 * whole branching command tree from the failure point, so a bare
 * {@code /height} (which branches into an argument child plus two literal
 * children - see {@code HeightCommand}) rendered every branch mashed
 * together into one confusing string rather than one clean placeholder.
 *
 * <p>Deliberately a hand-maintained list rather than introspecting Cloud's
 * command tree/{@code HelpHandler} at runtime: reconstructing a syntax
 * string from a {@code Command<C>} needs a {@code CommandSyntaxFormatter}
 * plus a {@code CommandNode}, and this session already hit two Cloud
 * runtime behaviours (a {@code SenderMapper} type-inference surprise, an
 * {@code AmbiguousNodeException}) that only surfaced on a live server, not
 * a local compile. A static list is trivially correct and easy to keep in
 * sync by eye against the other classes in this package.</p>
 *
 * <p>Every displayed syntax is clickable ({@link ClickEvent#suggestCommand}):
 * clicking inserts just the literal part of the command (everything before
 * its first {@code <...>}/{@code [...]} placeholder) into chat, ready for
 * the player to type the argument themselves, rather than the placeholder
 * text itself.</p>
 */
@RequiredArgsConstructor
public final class HelpCommand {

    /**
     * @param syntax the full display form, e.g. {@code "/height set <target> <meters>"} -
     *               {@code <type>} marks a freely-typed value (a number, a player name),
     *               {@code [keys]} marks one of a small closed set of textual keys (e.g. a
     *               configured gender key) - see {@link #clickCommand()} for how this is
     *               trimmed down to a click target.
     */
    private record Entry(String permission, String syntax, String descriptionKey) {

        /** Everything in {@link #syntax} before its first placeholder (dropping the placeholder itself), so a click leaves the player ready to type it. A syntax with no placeholder at all (already a complete command) is used as-is. */
        String clickCommand() {
            int angle = syntax.indexOf('<');
            int square = syntax.indexOf('[');
            int cut = angle < 0 ? square : square < 0 ? angle : Math.min(angle, square);
            return cut < 0 ? syntax : syntax.substring(0, cut);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("flectonegrowth.height", "/height <meters>", "help.height"),
            new Entry("flectonegrowth.height", "/height remove", "help.height-remove"),
            new Entry("flectonegrowth.height.others", "/height set <target> <meters>", "help.height-set-others"),
            new Entry("flectonegrowth.height.others", "/height remove <target>", "help.height-remove-others"),
            new Entry("flectonegrowth.gender", "/gender [keys]", "help.gender"),
            new Entry("flectonegrowth.gender.others", "/gender set <target> [keys]", "help.gender-others"),
            new Entry("flectonegrowth.info", "/growth summary", "help.growth-summary"),
            new Entry("flectonegrowth.info.others", "/growth summary <target>", "help.growth-summary-others"),
            new Entry("flectonegrowth.info", "/growth height", "help.growth-height"),
            new Entry("flectonegrowth.info.others", "/growth height <target>", "help.growth-height-others"),
            new Entry("flectonegrowth.info", "/growth gender", "help.growth-gender"),
            new Entry("flectonegrowth.info.others", "/growth gender <target>", "help.growth-gender-others"),
            new Entry("flectonegrowth.admin.reload", "/flectonegrowth reload", "help.reload"),
            new Entry(null, "/flectonegrowth help", "help.help")
    );

    private final FlectoneGrowthCore core;

    @Command("flectonegrowth|pg help")
    @CommandDescription("Lists every FlectoneGrowth command you can use.")
    @Permission("flectonegrowth.help")
    public void help(CommandSender sender) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("help.header"));
        for (Entry entry : ENTRIES) {
            if (entry.permission() != null && !sender.hasPermission(entry.permission())) continue;
            lines.add(new Line("help.entry", Map.of(
                    "syntax", syntaxComponent(entry),
                    "description", core.messages().raw(entry.descriptionKey()))));
        }
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }

    /**
     * The clean, clickable syntax for whichever command {@code chain} (the
     * literal path Cloud successfully matched before throwing an
     * {@code InvalidSyntaxException} - see
     * {@code CommandParseException#currentChain()}, used by
     * {@code CommandRegistry}) identifies - empty if the chain doesn't
     * uniquely identify exactly one entry (e.g. a bare {@code /height}
     * matches all four height entries), so the caller can fall back to
     * something generic instead of guessing which one the sender meant.
     */
    public static Optional<Component> syntaxFor(CommandSender sender, List<CommandComponent<?>> chain) {
        StringBuilder prefix = new StringBuilder("/");
        boolean first = true;
        for (CommandComponent<?> component : chain) {
            if (component.type() != CommandComponent.ComponentType.LITERAL) break;
            if (!first) prefix.append(' ');
            prefix.append(component.name());
            first = false;
        }
        String prefixStr = prefix.toString();

        List<Entry> matches = ENTRIES.stream()
                .filter(entry -> entry.permission() == null || sender.hasPermission(entry.permission()))
                .filter(entry -> entry.syntax().startsWith(prefixStr))
                .toList();

        return matches.size() == 1 ? Optional.of(syntaxComponent(matches.get(0))) : Optional.empty();
    }

    private static Component syntaxComponent(Entry entry) {
        return Component.text(entry.syntax()).clickEvent(ClickEvent.suggestCommand(entry.clickCommand()));
    }
}
