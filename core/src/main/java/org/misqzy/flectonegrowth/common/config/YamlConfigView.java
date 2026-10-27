package org.misqzy.flectonegrowth.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ConfigView} backed by a plain {@code Map<String,Object>}, the
 * structure SnakeYAML produces when loading a YAML document. Because this
 * only depends on SnakeYAML (a plain Java library, not Bukkit) it can be
 * reused unchanged by every platform module.
 */
public final class YamlConfigView implements ConfigView {

    private final Map<?, ?> root;

    public YamlConfigView(Map<?, ?> root) {
        this.root = root != null ? root : Map.of();
    }

    private Object resolve(String path) {
        Object current = root;
        // Hand-rolled split on '.': this runs on every message/PlaceholderAPI lookup, so no array/regex per call.
        int start = 0;
        int len = path.length();
        while (true) {
            if (!(current instanceof Map<?, ?> map)) return null;
            int dot = path.indexOf('.', start);
            int end = dot < 0 ? len : dot;
            current = map.get(path.substring(start, end));
            if (dot < 0) return current;
            start = dot + 1;
        }
    }

    @Override
    public String getString(String path, String def) {
        Object v = resolve(path);
        return v != null ? String.valueOf(v) : def;
    }

    @Override
    public int getInt(String path, int def) {
        Object v = resolve(path);
        return v instanceof Number n ? n.intValue() : def;
    }

    @Override
    public long getLong(String path, long def) {
        Object v = resolve(path);
        return v instanceof Number n ? n.longValue() : def;
    }

    @Override
    public double getDouble(String path, double def) {
        Object v = resolve(path);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = resolve(path);
        return v instanceof Boolean b ? b : def;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object v = resolve(path);
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public ConfigView getSection(String path) {
        Object v = resolve(path);
        return v instanceof Map<?, ?> map ? new YamlConfigView(map) : null;
    }

    @Override
    public Set<String> keys() {
        Set<String> out = new LinkedHashSet<>();
        for (Object k : root.keySet()) out.add(String.valueOf(k));
        return out;
    }
}
