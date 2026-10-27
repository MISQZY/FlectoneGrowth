package org.misqzy.flectonegrowth.common.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigViewTest {

    private final ConfigView view = new YamlConfigView(new Yaml().load("""
            a:
              b:
                c: hello
                n: 7
                d: 1.5
                flag: true
                list: [x, 2]
              empty: ''
            top: 3
            """));

    @Test
    void resolvesNestedPaths() {
        assertEquals("hello", view.getString("a.b.c", "def"));
        assertEquals(7, view.getInt("a.b.n", -1));
        assertEquals(7L, view.getLong("a.b.n", -1));
        assertEquals(1.5, view.getDouble("a.b.d", -1));
        assertTrue(view.getBoolean("a.b.flag", false));
        assertEquals(List.of("x", "2"), view.getStringList("a.b.list"));
        assertEquals("3", view.getString("top", "def"));
        assertEquals("", view.getString("a.empty", "def"));
    }

    @Test
    void missingPathsAndWrongTypesFallBack() {
        assertEquals("def", view.getString("a.x.c", "def"));
        assertEquals("def", view.getString("nope", "def"));
        assertEquals(-1, view.getInt("a.b.c", -1));
        assertFalse(view.getBoolean("a.b.c", false));
        assertEquals(List.of(), view.getStringList("a.b.c"));
        assertNull(view.getSection("a.b.c"));
        assertEquals("def", view.getString("top.deeper", "def")); // descending through a scalar
    }

    @Test
    void trailingDotLeadingDotAndEmptyPathResolveToNothing() {
        assertEquals("def", view.getString("a.", "def"));
        assertNull(view.getSection("a."));
        assertEquals("def", view.getString("a.b.", "def"));
        assertEquals("def", view.getString(".a", "def"));
        assertEquals("def", view.getString("", "def"));
        assertEquals("def", view.getString("a..b", "def"));
    }

    @Test
    void sectionsAndKeys() {
        ConfigView b = view.getSection("a.b");
        assertNotNull(b);
        assertEquals("hello", b.getString("c", "def"));
        assertEquals(java.util.Set.of("b", "empty"), view.getSection("a").keys());
        assertEquals(java.util.Set.of("a", "top"), view.keys());
    }

    @Test
    void nullRootIsEmpty() {
        ConfigView empty = new YamlConfigView(null);
        assertTrue(empty.keys().isEmpty());
        assertEquals("def", empty.getString("a", "def"));
        assertEquals("def", new YamlConfigView(Map.of()).getString("a.b", "def"));
    }
}
