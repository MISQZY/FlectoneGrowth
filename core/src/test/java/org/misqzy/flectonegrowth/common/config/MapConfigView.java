package org.misqzy.flectonegrowth.common.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tiny flat ConfigView over a map, for tests: the path is looked up verbatim as a key. */
public record MapConfigView(Map<String, String> values) implements ConfigView {
    @Override public String getString(String path, String def) { return values.getOrDefault(path, def); }
    @Override public int getInt(String path, int def) { return def; }
    @Override public long getLong(String path, long def) { return def; }
    @Override public double getDouble(String path, double def) { return def; }
    @Override public boolean getBoolean(String path, boolean def) { return def; }
    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public ConfigView getSection(String path) { return null; }
    @Override public Set<String> keys() { return values.keySet(); }
}
