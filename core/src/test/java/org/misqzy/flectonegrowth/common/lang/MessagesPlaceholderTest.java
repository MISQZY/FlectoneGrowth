package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.misqzy.flectonegrowth.common.lang.MessagesFixtures.*;

class MessagesPlaceholderTest {

    private static final String[] NASTY = {
            "<red>Bob", "&c&lX", "<primary>hi</primary>", "back\\slash", "it's", "say \"hi\"", "a<b>c", "tail\\", "<", "§6gold"
    };

    private static String clickValue(Component c) {
        return ((ClickEvent.Payload.Text) c.clickEvent().payload()).value();
    }

    @Test
    void valuesRenderLiterallyInGet() {
        for (String v : NASTY) {
            Messages m = themed("[<name>] <primary>[<name>]</primary>!");
            Component c = m.get("en", "k", Map.of("name", v));
            assertEquals("[" + v + "] [" + v + "]!", plain(c), "value: " + v);
        }
    }

    @Test
    void valuesSurviveDispatchRoundTrip() {
        for (String v : NASTY) {
            Messages m = themed("[<name>] <primary>[<name>]</primary>!");
            String d = m.rawForDispatch("en", "k", Map.of("name", v));
            Component c = assertDoesNotThrow(() -> MM.deserialize(d), "value: " + v + " -> " + d);
            String expected = Messages.neutralizeLegacy(v); // the section sign is defanged with a zero-width space
            assertEquals("[" + expected + "] [" + expected + "]!", plain(c), "value: " + v);
        }
    }

    @Test
    void sectionSignDoesNotThrowAnywhere() {
        Messages m = themed("<name> <click:suggest_command:'/x <cmd>'>c</click>");
        Map<String, Object> ph = Map.of("name", "§4§lbold §", "cmd", new Messages.TemplateText("§6"));
        assertDoesNotThrow(() -> m.get("en", "k", ph));
        assertDoesNotThrow(() -> MM.deserialize(m.rawForDispatch("en", "k", ph)));
    }

    @Test
    void uppercasePlaceholderKeysAndTagsMatch() {
        Messages m = themed("<NAME> <name> <Name>");
        assertEquals("v v v", plain(m.get("en", "k", Map.of("NAME", "v"))));
        assertEquals("v v v", plain(MM.deserialize(m.rawForDispatch("en", "k", Map.of("NAME", "v")))));
    }

    @Test
    void nullAndEmptyPlaceholderMapsAreFine() {
        Messages m = themed("<primary>x</primary>");
        assertEquals("x", plain(m.get("en", "k", null)));
        assertEquals("<gold>x</gold>", m.rawForDispatch("en", "k", null));
        assertEquals("x", plain(m.get("en", "k", Map.of())));
    }

    // ---- TemplateText ------------------------------------------------

    @Test
    void templateTextInSingleQuotedClick() {
        String payload = "a<b>'c\\";
        Component c = themed("<click:suggest_command:'/x <cmd> z'>c</click>")
                .get("en", "k", Map.of("cmd", new Messages.TemplateText(payload)));
        assertNotNull(c.clickEvent());
        assertEquals("/x " + payload + " z", clickValue(c));
    }

    @Test
    void templateTextInDoubleQuotedClick() {
        String payload = "q\"uote<>\\";
        Component c = themed("<click:run_command:\"/x <cmd>\">c</click>")
                .get("en", "k", Map.of("cmd", new Messages.TemplateText(payload)));
        assertEquals("/x " + payload, clickValue(c));
    }

    @Test
    void templateTextInOpenUrl() {
        String tpl = "<click:open_url:'<url>'>d</click>";
        Component c = themed(tpl).get("en", "k", Map.of("url", new Messages.TemplateText("https://x.y/z?a=1&b=2")));
        assertEquals("https://x.y/z?a=1&b=2", clickValue(c));
        String d = themed(tpl).rawForDispatch("en", "k", Map.of("url", new Messages.TemplateText("https://x.y/z")));
        assertEquals("https://x.y/z", clickValue(MM.deserialize(d)));
    }

    @Test
    void templateTextInHoverEscapesLessThan() {
        Component c = themed("<hover:show_text:'tip <t>'>h</hover>")
                .get("en", "k", Map.of("t", new Messages.TemplateText("<red>x</red>")));
        HoverEvent<?> hv = c.hoverEvent();
        assertNotNull(hv);
        assertEquals("tip <red>x</red>", plain((Component) hv.value()));
    }

    @Test
    void templateTextInPlainBodyIsEscapedLiteral() {
        Component c = themed("[<t>]").get("en", "k", Map.of("t", new Messages.TemplateText("<red>x\\")));
        assertEquals("[<red>x\\]", plain(c));
    }

    @Test
    void templateTextNullValueIsEmpty() {
        Component c = themed("[<t>]").get("en", "k", Map.of("t", new Messages.TemplateText(null)));
        assertEquals("[]", plain(c));
    }

    @Test
    void usageHoverAndClickLikeTheBundledMessage() {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("usage", "/height <player>");
        ph.put("command", new Messages.TemplateText("/height "));
        Messages m = themed("<hover:show_text:'<secondary>Use <br><primary><usage>'><click:suggest_command:'<command>'><primary><usage></click></hover>");
        Component c = m.get("en", "k", ph);
        assertEquals("/height <player>", plain(c));
        assertEquals("/height ", clickValue(c));
        assertDoesNotThrow(() -> MM.deserialize(m.rawForDispatch("en", "k", ph)));
    }
}
