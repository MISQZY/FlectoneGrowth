package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.misqzy.flectonegrowth.common.config.MapConfigView;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.misqzy.flectonegrowth.common.lang.MessagesFixtures.*;

class MessagesThemeTest {

    private static String dispatch(String primary, String secondary, String tpl) {
        return messages(primary, secondary, Map.of(), tpl).rawForDispatch("en", "k", Map.of());
    }

    // ---- value forms -------------------------------------------------

    @Test
    void namedColor() {
        assertEquals("<gold>A</gold>", dispatch("gold", "aqua", "<primary>A</primary>"));
        assertEquals("<aqua>B</aqua>", dispatch("gold", "aqua", "<secondary>B</secondary>"));
    }

    @Test
    void legacyAmpersandAndSectionHex() {
        assertEquals("<gold>A</gold>", dispatch("&6", "", "<primary>A</primary>"));
        assertEquals("<red><bold>B</bold></red>", dispatch("&c&l", "", "<primary>B</primary>"));
        assertEquals("<#ff0000>C</#ff0000>", dispatch("§x§f§f§0§0§0§0", "", "<primary>C</primary>"));
        assertEquals("<#ff0000>D</#ff0000>", dispatch("&x&F&F&0&0&0&0", "", "<primary>D</primary>"));
        assertEquals("<#abcdef>E</#abcdef>", dispatch("&#abcdef", "", "<primary>E</primary>"));
    }

    @Test
    void hexForms() {
        assertEquals("<#ffd700>A</#ffd700>", dispatch("#FFD700", "", "<primary>A</primary>"));
        assertEquals("<#ffd700>A</#ffd700>", dispatch("ffd700", "", "<primary>A</primary>"));
        assertEquals("<#aabbcc>A</#aabbcc>", dispatch("#abc", "", "<primary>A</primary>"));
    }

    @Test
    void gradientKeepsParamsAndClosesByName() {
        assertEquals("<gradient:#ff0000:#00ff00>Hi</gradient> there",
                dispatch("<gradient:#ff0000:#00ff00>", "", "<primary>Hi</primary> there"));
    }

    @Test
    void multipleTagsCloseInReverseOrder() {
        assertEquals("<b><red>A</red></b>B", dispatch("<b><red>", "", "<primary>A</primary>B"));
    }

    @Test
    void blankOrNullOrGarbageEmitsNothing() {
        assertEquals("AB", dispatch("", null, "<primary>A</primary><secondary>B</secondary>"));
        assertEquals("AB", dispatch("   ", "not-a-color", "<primary>A</primary><secondary>B</secondary>"));
        assertEquals("A", dispatch("&zz", "", "<primary>A</primary>"));
    }

    // ---- structure ---------------------------------------------------

    @Test
    void nesting() {
        assertEquals("<gold>A <aqua>B</aqua> C</gold> D",
                dispatch("gold", "aqua", "<primary>A <secondary>B</secondary> C</primary> D"));
    }

    @Test
    void adjacent() {
        assertEquals("<gold>A</gold><aqua>B</aqua>C",
                dispatch("gold", "aqua", "<primary>A</primary><secondary>B</secondary>C"));
    }

    @Test
    void crossedOrderStillProducesValidMarkup() {
        String out = dispatch("gold", "aqua", "<primary>A<secondary>B</primary>C</secondary>D");
        assertEquals("ABCD", plain(MM.deserialize(out)));
        assertEquals("<gold>A<aqua>B</aqua></gold>CD", out);
    }

    @Test
    void strayClosersEmitNothing() {
        assertEquals("AB", dispatch("gold", "aqua", "A</primary>B</secondary>"));
        assertEquals("A", dispatch("gold", "aqua", "A</fcolor>"));
        assertEquals("A", dispatch("gold", "aqua", "A</fcolor:3>"));
    }

    @Test
    void escapedTagIsLeftAlone() {
        Component c = themed("\\<primary>x").get("en", "k");
        assertEquals("<primary>x", plain(c));
    }

    @Test
    void unterminatedBracketAndPlainLessThan() {
        Component c = themed("a < b <primary>c</primary").get("en", "k");
        assertEquals("a < b c</primary", plain(c)); // the unterminated closer is literal text
    }

    @Test
    void themeTagInsideHoverIsExpanded() {
        String out = dispatch("gold", "aqua", "<hover:show_text:'<primary>tip</primary>'>hov</hover>");
        assertEquals("<hover:show_text:'<gold>tip</gold>'>hov</hover>", out);
        assertEquals("hov", plain(MM.deserialize(out)));
    }

    @Test
    void localRenderMatchesDispatchRenderForPlainColors() {
        // the serializer omits closers at the very end of the text
        assertEquals("<gold>A", serialized(themed("<primary>A</primary>").get("en", "k")));
    }

    @Test
    void missingKeyIsVisibleRedPlaceholder() {
        Messages m = themed("x");
        assertEquals("<red>Missing message: nope</red>", m.raw("en", "nope"));
        assertNull(m.rawOrNull("en", "nope"));
        assertEquals("Missing message: nope", plain(m.get("en", "nope")));
    }

    @Test
    void unknownLocaleFallsBackToDefault() {
        Messages m = themed("hello");
        assertFalse(m.hasLocale("de"));
        assertEquals("hello", plain(m.get("de", "k")));
    }

    @Test
    void emptyLocaleMapDoesNotThrow() {
        Messages m = new Messages(Map.of(), "en", "gold", "aqua", null);
        assertEquals("Missing message: k", plain(m.get("k")));
    }

    @Test
    void sectionSignInTemplateDoesNotThrow() {
        Messages m = new Messages(Map.of("en", new MapConfigView(Map.of("k", "a §6 b <primary>c</primary>"))),
                "en", "gold", "aqua", null);
        assertDoesNotThrow(() -> m.get("k"));
        assertDoesNotThrow(() -> m.rawForDispatch("k", Map.of()));
    }

    @Test
    void neutralizeLegacy() {
        assertEquals("§​c", Messages.neutralizeLegacy("§c"));
        assertEquals("&c", Messages.neutralizeLegacy("&c"));
        assertNull(Messages.neutralizeLegacy(null));
        assertEquals("", Messages.neutralizeLegacy(""));
    }
}
