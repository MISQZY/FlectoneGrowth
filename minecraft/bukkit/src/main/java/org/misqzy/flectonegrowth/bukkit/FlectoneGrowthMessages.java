package org.misqzy.flectonegrowth.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.misqzy.flectonegrowth.bukkit.integration.FlectonePulseMessageDispatcher;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.lang.Messages;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single entry point every command class sends a message through, instead
 * of calling {@code core.messages().get(...)} + {@code LegacyText.send(...)}
 * directly. If {@code sender} is an online {@link Player} and FlectonePulse
 * is installed and tracking them, the message is routed through
 * {@link FlectonePulseMessageDispatcher} instead - so {@code <fcolor:N>} and
 * delivery both go through FlectonePulse itself, matching how its own
 * messages behave, rather than this plugin reimplementing either. Any other
 * sender (console, or a player FlectonePulse doesn't have ready), or
 * FlectonePulse simply not being installed, falls back to this plugin's own
 * {@code Messages} + {@link LegacyText} rendering unchanged.
 *
 * <p>A {@link Player} sender's message locale is picked from their own
 * Minecraft client locale ({@link Player#getLocale()}, e.g. {@code "ru_ru"} -
 * only the language segment before the underscore is used) when a matching
 * {@code localizations/messages_<code>.yml} is loaded, falling back to
 * config.yml's configured {@code locale} otherwise (console senders always
 * use the configured locale - there's no client locale to read). This is
 * also what FlectonePulse's own translate module does under the hood when
 * its {@code language.by-player} setting is on (verified by decompiling its
 * real jar - it has no per-player language of its own to read either), so
 * this stays consistent with it without needing any FlectonePulse API.</p>
 */
public final class FlectoneGrowthMessages {

    private FlectoneGrowthMessages() {}

    public static void send(FlectoneGrowthCore core, CommandSender sender, String key) {
        send(core, sender, key, Map.of());
    }

    public static void send(FlectoneGrowthCore core, CommandSender sender, String key, Map<String, Object> placeholders) {
        Messages messages = core.messages();

        if (sender instanceof Player player) {
            String locale = clientLocale(messages, player);
            if (core.integrations().flectonePulseMessageDispatch()
                    && tryFlectonePulseSend(player, messages, locale, key, placeholders)) {
                return;
            }
            LegacyText.send(sender, messages.get(locale, key, placeholders));
            return;
        }

        LegacyText.send(sender, messages.get(key, placeholders));
    }

    /** One localized line within a {@link #sendLines} call - a message key plus that line's own placeholders. */
    public record Line(String key, Map<String, Object> placeholders) {
        public Line(String key) {
            this(key, Map.of());
        }
    }

    /**
     * Renders every one of {@code lines} and sends them as a single message
     * with a line break between each, instead of one {@link #send} call per
     * line - {@code /growth summary} used to arrive as several separate chat
     * entries for what is conceptually one piece of output.
     *
     * <p>Always renders locally - deliberately does not attempt
     * {@link FlectonePulseMessageDispatcher} routing, the same call
     * {@link #send} makes per line. Combining several distinct message keys
     * (each with their own placeholders) into one FlectonePulse dispatch
     * call has no safe mapping onto its one-raw-string-plus-one-resolver-set
     * API, and this is composite command/system output rather than a single
     * chat message - the same reasoning {@code common.notify.Notifier}
     * already applies to its own update notice.</p>
     */
    public static void sendLines(FlectoneGrowthCore core, CommandSender sender, List<Line> lines) {
        if (lines.isEmpty()) return;
        Messages messages = core.messages();
        String locale = sender instanceof Player player ? clientLocale(messages, player) : null;

        List<Component> rendered = lines.stream()
                .map(line -> messages.get(locale, line.key(), line.placeholders()))
                .toList();
        LegacyText.send(sender, Component.join(JoinConfiguration.newlines(), rendered));
    }

    /**
     * The locale {@code sender} will actually see a message rendered in -
     * {@code null} for a non-player sender (console) or a player whose
     * client locale has no matching translation, meaning "use config.yml's
     * configured default", exactly like {@link #send}/{@link #sendLines}
     * resolve it internally. Exposed for a caller that needs to pre-resolve
     * a locale-sensitive value (e.g. {@code FlectoneGrowthCore#genderDisplayName})
     * before building the placeholder map handed to those methods - without
     * this, that value would always render in the configured default locale
     * regardless of what locale the rest of the message ends up in,
     * mismatching it for a player whose client locale differs.
     */
    public static String resolveLocale(FlectoneGrowthCore core, CommandSender sender) {
        return sender instanceof Player player ? clientLocale(core.messages(), player) : null;
    }

    /** {@code player}'s client locale's language segment (e.g. {@code "ru_ru"} -> {@code "ru"}) if a matching translation file is loaded, else config.yml's configured default. */
    private static String clientLocale(Messages messages, Player player) {
        String raw = player.getLocale();
        if (raw == null || raw.isBlank()) return null;

        int separator = raw.indexOf('_');
        String language = (separator > 0 ? raw.substring(0, separator) : raw).toLowerCase(Locale.ROOT);
        return messages.hasLocale(language) ? language : null;
    }

    /**
     * Guards the call into {@link FlectonePulseMessageDispatcher} itself, not
     * just its body - a real live-deploy crash: {@code trySend}'s own
     * internal try/catch only covers failures while its bytecode is
     * *executing*, but with FlectonePulse not installed, the JVM fails to
     * *link/verify* the {@link FlectonePulseMessageDispatcher} class the
     * first time any of its methods is actively called at all (its bytecode
     * needs FlectonePulse's {@code FEntity} type resolvable to type-check
     * {@code MessageContext.Builder#sender(FEntity)}/{@code receiver(FEntity)}).
     * That failure - a {@link LinkageError} - is thrown at the call site in
     * the *caller*, before a single instruction of {@code trySend} ever
     * runs, so no try/catch inside that method can ever see it. Same root
     * cause as {@code FlectonePulseAccess.tryGetFileFacade}'s fix, one class
     * over.
     */
    private static boolean tryFlectonePulseSend(Player player, Messages messages, String locale, String key, Map<String, Object> placeholders) {
        try {
            return FlectonePulseMessageDispatcher.trySend(player, messages.rawForDispatch(locale, key), messages.externalDispatchResolvers(placeholders));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
