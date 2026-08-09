package org.misqzy.flectonegrowth.bukkit.config;

import org.misqzy.flectonegrowth.common.config.ConfigView;
import org.misqzy.flectonegrowth.common.config.YamlConfigView;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Loads a YAML file on disk into a platform-agnostic {@link ConfigView}. */
public final class YamlFileLoader {

    private YamlFileLoader() {}

    public static ConfigView load(File file) {
        if (!file.isFile()) {
            throw new IllegalStateException("Required resource is missing, expected it at: " + file
                    + " - if this was deleted or failed to install, try removing the plugin's data folder and restarting.");
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return new YamlConfigView(loaded instanceof Map<?, ?> map ? map : Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load YAML file: " + file, e);
        } catch (YAMLException e) {
            // A syntax error (bad indentation, an unclosed quote, ...) throws YAMLException - an unchecked
            // RuntimeException, not an IOException - straight out of Yaml#load. Uncaught, this surfaced as a
            // raw stack trace crashing plugin enable, or (via /flectonegrowth reload, whose whole body runs
            // on an async task) an async task that just dies mid-reload with no user-facing feedback at all -
            // wrapped here into the same crafted, admin-legible message every other load failure already gets.
            throw new IllegalStateException("Failed to parse YAML file (syntax error): " + file + " - " + e.getMessage(), e);
        }
    }
}
