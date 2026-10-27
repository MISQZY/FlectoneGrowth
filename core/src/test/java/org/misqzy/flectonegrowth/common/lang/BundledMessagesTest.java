package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.misqzy.flectonegrowth.common.config.MapConfigView;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.misqzy.flectonegrowth.common.lang.MessagesFixtures.*;

/** Renders every key of the real bundled localisation files under several theme configurations. */
class BundledMessagesTest {

    private record Theme(String name, String primary, String secondary, Map<Integer, String> palette) {}

    private static final Map<Integer, String> FULL = new HashMap<>(PALETTE);
    static {
        FULL.put(4, "gold");
        FULL.put(5, "<b><gradient:#a1b2c3:#d4e5f6>");
    }

    private static final List<Theme> THEMES = List.of(
            new Theme("fcolor", "<fcolor:1>", "<fcolor:2>", FULL),
            new Theme("fcolor-no-palette", "<fcolor:1>", "<fcolor:2>", Map.of()),
            new Theme("fcolor-missing-index", "<fcolor:9>", "<fcolor:3>", FULL),
            new Theme("fcolor+style", "<fcolor:1><b>", "<fcolor:5>", FULL),
            new Theme("gold-aqua", "gold", "aqua", Map.of()),
            new Theme("gradient", "<gradient:#ff0000:#00ff00>", "<fcolor:2>", FULL),
            new Theme("legacy-hex", "&6", "#abc", Map.of()),
            new Theme("blank", "", "", Map.of()));

    /** Anything theme-like that must never be visible as text. */
    private static final Pattern LEFTOVER = Pattern.compile(
            "</?(fcolor|primary|secondary|gradient|gold|aqua|b|bold)\\b[^>]*>|<#[0-9a-fA-F]{3,6}>");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-zA-Z][a-zA-Z0-9_-]*)>");

    private static Path localizationsDir() {
        String prop = System.getProperty("localizations.dir");
        Path dir = prop != null ? Path.of(prop) : Path.of("..", "minecraft", "bukkit", "src", "main", "resources", "localizations");
        assertTrue(Files.isDirectory(dir), "localizations dir not found: " + dir.toAbsolutePath());
        return dir;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node instanceof String s) {
            out.put(prefix, s);
        }
    }

    private static Map<String, String> load(String lang) throws IOException {
        Map<String, String> msgs = new LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(localizationsDir().resolve("messages_" + lang + ".yml"), StandardCharsets.UTF_8)) {
            flatten("", new Yaml().load(r), msgs);
        }
        return msgs;
    }

    private static Map<String, Object> samples(String key, String template) {
        Map<String, Object> ph = new HashMap<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            if (name.equals("url") || key.contains("url")) {
                ph.put(name, new Messages.TemplateText("https://example.org/dl?x=1&y=2"));
            } else if (name.equals("command")) {
                ph.put(name, new Messages.TemplateText("/height "));
            } else {
                ph.put(name, "[" + name + "]");
            }
        }
        // a few that exist as reserved MiniMessage-ish names but are values for us
        ph.putIfAbsent("player", "Steve");
        ph.putIfAbsent("value", "1.22");
        return ph;
    }

    private static void assertNoLeftover(String label, String plain) {
        Matcher m = LEFTOVER.matcher(plain);
        assertFalse(m.find(), () -> label + ": leftover '" + m.group() + "' in: " + plain);
    }

    @TestFactory
    Stream<DynamicTest> everyKeyRendersCleanlyUnderEveryTheme() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String lang : List.of("en", "ru")) {
            Map<String, String> msgs = load(lang);
            assertFalse(msgs.isEmpty(), "no messages loaded for " + lang);
            MapConfigView view = new MapConfigView(msgs);
            for (Theme theme : THEMES) {
                tests.add(DynamicTest.dynamicTest(lang + " / " + theme.name() + " (" + msgs.size() + " keys)", () -> {
                    Messages m = new Messages(Map.of(lang, view), lang, theme.primary(), theme.secondary(), theme::palette);
                    for (Map.Entry<String, String> e : msgs.entrySet()) {
                        String key = e.getKey();
                        String label = lang + " " + key + " theme=" + theme.name();
                        Map<String, Object> ph = samples(key, e.getValue());

                        Component local = assertDoesNotThrow(() -> m.get(lang, key, ph), label + " get()");
                        assertNoLeftover(label + " [get]", plain(local));
                        assertFalse(serialized(local).contains("fcolor"), label + " fcolor in local MiniMessage");

                        String dispatch = assertDoesNotThrow(() -> m.rawForDispatch(lang, key, ph), label + " rawForDispatch()");
                        assertFalse(dispatch.contains("</fcolor"), label + " </fcolor in dispatch: " + dispatch);
                        assertFalse(dispatch.contains("<primary") || dispatch.contains("<secondary")
                                || dispatch.contains("</primary") || dispatch.contains("</secondary"), label + " theme tag in dispatch: " + dispatch);
                        Component viaPulse = assertDoesNotThrow(
                                () -> MM.deserialize(resolveFcolor(dispatch, theme.palette())), label + " dispatch parse: " + dispatch);
                        assertNoLeftover(label + " [dispatch]", plain(viaPulse));

                        // the no-placeholder overload must be safe too
                        assertDoesNotThrow(() -> m.get(lang, key), label + " get(no placeholders)");
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> bundledLocalesHaveTheSameKeys() throws IOException {
        Map<String, String> en = load("en");
        Map<String, String> ru = load("ru");
        return Stream.of(
                DynamicTest.dynamicTest("ru has every en key", () -> {
                    List<String> missing = new ArrayList<>(en.keySet());
                    missing.removeAll(ru.keySet());
                    assertTrue(missing.isEmpty(), "missing in ru: " + missing);
                }),
                DynamicTest.dynamicTest("en has every ru key", () -> {
                    List<String> missing = new ArrayList<>(ru.keySet());
                    missing.removeAll(en.keySet());
                    assertTrue(missing.isEmpty(), "missing in en: " + missing);
                }));
    }
}
