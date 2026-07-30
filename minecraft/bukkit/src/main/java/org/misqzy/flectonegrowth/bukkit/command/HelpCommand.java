package org.misqzy.flectonegrowth.bukkit.command;

import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages;
import org.misqzy.flectonegrowth.bukkit.FlectoneGrowthMessages.Line;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lists every FlectoneGrowth command the sender has permission to use.
 *
 * <p>Deliberately a hand-maintained list rather than introspecting Cloud's
 * command tree/{@code HelpHandler} at runtime: reconstructing a syntax
 * string from a {@code Command<C>} needs a {@code CommandSyntaxFormatter}
 * plus a {@code CommandNode}, and this session already hit two Cloud
 * runtime behaviours (a {@code SenderMapper} type-inference surprise, an
 * {@code AmbiguousNodeException}) that only surfaced on a live server, not
 * a local compile. A static list is trivially correct and easy to keep in
 * sync by eye against the other classes in this package.</p>
 */
public final class HelpCommand {

    private record Entry(String permission, String syntax, String descriptionKey) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry("flectonegrowth.height", "/height <meters>", "help.height"),
            new Entry("flectonegrowth.height", "/height remove", "help.height-remove"),
            new Entry("flectonegrowth.height.others", "/height set <target> <meters>", "help.height-set-others"),
            new Entry("flectonegrowth.height.others", "/height remove <target>", "help.height-remove-others"),
            new Entry("flectonegrowth.gender", "/gender <type>", "help.gender"),
            new Entry("flectonegrowth.gender.others", "/gender set <target> <type>", "help.gender-others"),
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

    public HelpCommand(FlectoneGrowthCore core) {
        this.core = core;
    }

    @Command("flectonegrowth|pg help")
    @CommandDescription("Lists every FlectoneGrowth command you can use.")
    @Permission("flectonegrowth.help")
    public void help(CommandSender sender) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("help.header"));
        for (Entry entry : ENTRIES) {
            if (entry.permission() != null && !sender.hasPermission(entry.permission())) continue;
            lines.add(new Line("help.entry", Map.of(
                    "syntax", entry.syntax(),
                    "description", core.messages().raw(entry.descriptionKey()))));
        }
        FlectoneGrowthMessages.sendLines(core, sender, lines);
    }
}
