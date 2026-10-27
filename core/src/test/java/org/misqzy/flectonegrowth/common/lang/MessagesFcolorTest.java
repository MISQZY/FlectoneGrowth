package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.misqzy.flectonegrowth.common.config.MapConfigView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.misqzy.flectonegrowth.common.lang.MessagesFixtures.*;

/** Regression: colors.primary/secondary configured as {@code <fcolor:N>} used to leak literal tags / unbalanced closers. */
class MessagesFcolorTest {

    private static final String HEIGHT_SET =
            "<primary>Set <secondary><player></secondary><primary>'s height to <secondary><value></secondary><primary> m.";
    private static final Map<String, Object> PH = Map.of("player", "MISQZY", "value", "1.22");

    private static Messages fcolorTheme(String tpl) {
        return messages("<fcolor:1>", "<fcolor:2>", PALETTE, tpl);
    }

    @Test
    void localRenderResolvesPaletteGradients() {
        Component c = fcolorTheme(HEIGHT_SET).get("en", "k", PH);
        String plain = plain(c);
        assertEquals("Set MISQZY's height to 1.22 m.", plain);
        assertFalse(plain.contains("<fcolor"));
        assertFalse(plain.contains("</fcolor"));
        assertFalse(plain.contains("<primary"));

        String mm = serialized(c).toLowerCase();
        assertTrue(mm.contains("<gradient:#a6d8ff:#8cc8ff>"), mm);
        assertTrue(mm.contains("<gradient:#6bb6ff:#4da6ff>"), mm);
        assertFalse(mm.contains("fcolor"), mm);
        // each secondary span is closed by a named </gradient>, then primary is re-opened (the serializer drops the final closer)
        assertEquals("<gradient:#a6d8ff:#8cc8ff>set <gradient:#6bb6ff:#4da6ff>misqzy</gradient>"
                + "<gradient:#a6d8ff:#8cc8ff>'s height to <gradient:#6bb6ff:#4da6ff>1.22</gradient>"
                + "<gradient:#a6d8ff:#8cc8ff> m.", mm);
    }

    @Test
    void localRenderClosesEachGradientWithItsOwnCloser() {
        Messages m = fcolorTheme("<primary>a</primary><secondary>b</secondary>c");
        String mm = serialized(m.get("en", "k"));
        assertEquals("<gradient:#a6d8ff:#8cc8ff>a</gradient><gradient:#6bb6ff:#4da6ff>b</gradient>c", mm.toLowerCase());
    }

    @Test
    void dispatchKeepsFcolorOpenersAndStripsAllFcolorClosers() {
        String d = fcolorTheme(HEIGHT_SET).rawForDispatch("en", "k", PH);
        assertFalse(d.contains("</fcolor"), d);
        assertTrue(d.contains("<fcolor:1>"), d);
        assertTrue(d.contains("<fcolor:2>"), d);
        assertFalse(d.contains("<primary"), d);
        assertFalse(d.contains("<secondary"), d);
        Component c = MM.deserialize(resolveFcolor(d, PALETTE));
        assertEquals("Set MISQZY's height to 1.22 m.", plain(c));
    }

    @Test
    void dispatchStripsFcolorClosersWrittenInTemplate() {
        String d = messages("gold", "aqua", Map.of(), "<fcolor:1>A</fcolor:1> <fcolor:2>B</fcolor> C</fcolor>")
                .rawForDispatch("en", "k", Map.of());
        assertEquals("<fcolor:1>A <fcolor:2>B C", d);
    }

    @Test
    void mixedFcolorAndStyleInTheme() {
        Messages m = messages("<fcolor:1><b>", "<fcolor:2>", PALETTE, "<primary>A</primary>B");
        assertEquals("<fcolor:1><b>A</b>B", m.rawForDispatch("en", "k", Map.of()));
        assertFalse(serialized(m.get("en", "k")).contains("fcolor"));
        assertEquals("AB", plain(m.get("en", "k")));
    }

    @Test
    void templateFcolorTagsLocal() {
        Messages m = messages("gold", "aqua", PALETTE,
                "<fcolor:1>A</fcolor:1> <fcolor:3>B</fcolor> <fcolor:9>C</fcolor:9> <fcolor:x>D</fcolor> E");
        Component c = m.get("en", "k");
        assertEquals("A B C D E", plain(c));
        assertFalse(serialized(c).contains("fcolor"));
    }

    @Test
    void bareFcolorCloserClosesMostRecentlyOpened() {
        Messages m = messages("gold", "aqua", PALETTE, "<fcolor:1>A<fcolor:2>B</fcolor>C</fcolor>D");
        Component c = m.get("en", "k");
        assertEquals("ABCD", plain(c));
        String mm = serialized(c).toLowerCase();
        assertTrue(mm.contains("#6bb6ff"), mm);
        assertFalse(mm.contains("fcolor"), mm);
        assertEquals(count(mm, "<gradient:"), count(mm, "</gradient>"), mm);
    }

    @Test
    void missingPaletteEntryDegradesToNoColor() {
        Messages m = messages("<fcolor:9>", "<fcolor:3>", PALETTE, "<primary>A</primary><secondary>B</secondary>");
        Component c = m.get("en", "k");
        assertEquals("AB", plain(c));
        assertFalse(serialized(c).contains("fcolor"));
    }

    @Test
    void emptyThrowingAndNullPaletteSuppliers() {
        String tpl = "<primary>A <secondary>B</secondary></primary>";
        List<Supplier<Map<Integer, String>>> suppliers = List.of(
                Map::of,
                () -> { throw new IllegalStateException("FlectonePulse not ready"); },
                () -> null);
        for (Supplier<Map<Integer, String>> s : suppliers) {
            Messages m = messages("<fcolor:1>", "<fcolor:2>", s, tpl);
            assertEquals("A B", plain(m.get("en", "k")));
            assertFalse(serialized(m.get("en", "k")).contains("fcolor"));
            String d = m.rawForDispatch("en", "k", Map.of());
            assertEquals("A B", plain(MM.deserialize(d.replaceAll("<fcolor:\\d+>", ""))));
        }
        Messages m = new Messages(Map.of("en", new MapConfigView(Map.of("k", tpl))),
                "en", "<fcolor:1>", "<fcolor:2>", null);
        assertEquals("A B", plain(m.get("en", "k")));
    }

    @Test
    void paletteEntryContainingFcolorIsNotRecursed() {
        Messages m = messages("<fcolor:1>", "", Map.of(1, "<fcolor:1><b>"), "<primary>A</primary>");
        assertEquals("A", plain(m.get("en", "k")));
    }

    @Test
    void paletteChangesAreSeenOnNextRender() {
        AtomicReference<Map<Integer, String>> pal = new AtomicReference<>(Map.of(1, "red"));
        Messages m = messages("<fcolor:1>", "", pal::get, "<primary>A</primary>");
        assertEquals("<red>A", serialized(m.get("en", "k")));
        pal.set(Map.of(1, "green"));
        assertEquals("<green>A", serialized(m.get("en", "k")));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
