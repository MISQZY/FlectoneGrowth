package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.misqzy.flectonegrowth.common.config.MapConfigView;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for the Messages tests. */
final class MessagesFixtures {
    static final MiniMessage MM = MiniMessage.miniMessage();
    static final Map<Integer, String> PALETTE = Map.of(
            1, "<gradient:#A6D8FF:#8CC8FF>",
            2, "<gradient:#6BB6FF:#4DA6FF>",
            3, "#A9A9A9");

    private MessagesFixtures() {}

    /** One locale "en" with the single message {@code k = template}. */
    static Messages messages(String primary, String secondary, Map<Integer, String> palette, String template) {
        return messages(primary, secondary, () -> palette, template);
    }

    static Messages messages(String primary, String secondary, Supplier<Map<Integer, String>> palette, String template) {
        return new Messages(Map.of("en", new MapConfigView(Map.of("k", template))), "en", primary, secondary, palette);
    }

    static Messages themed(String template) {
        return messages("gold", "aqua", Map.of(), template);
    }

    /** Concatenated literal text of the component tree (TextComponent contents only). */
    static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof TextComponent t) sb.append(t.content());
        for (Component child : c.children()) sb.append(plain(child));
        return sb.toString();
    }

    static String serialized(Component c) {
        return MM.serialize(c);
    }

    /** Simulates FlectonePulse: resolves only the opening {@code <fcolor:N>} tags from the palette. */
    static String resolveFcolor(String dispatch, Map<Integer, String> palette) {
        Matcher m = Pattern.compile("<fcolor:(\\d+)>").matcher(dispatch);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(palette.getOrDefault(Integer.parseInt(m.group(1)), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
