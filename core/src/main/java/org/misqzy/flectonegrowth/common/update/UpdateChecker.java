package org.misqzy.flectonegrowth.common.update;

import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks a GitHub repository's tags for a version newer than the one
 * currently running, with zero Bukkit dependency so any platform module can
 * reuse it. Deliberately reads {@code /repos/{owner}/{repo}/tags} rather
 * than {@code /repos/{owner}/{repo}/releases/latest} - this project doesn't
 * publish GitHub Releases (verified live: that endpoint 404s), only tags
 * (e.g. {@code v0.1.3}), so releases/latest would never report an update at
 * all.
 *
 * <p>The response is parsed with SnakeYAML rather than adding a JSON
 * library: JSON is a subset of YAML's flow style, and {@code core} already
 * depends on SnakeYAML for config loading, so this needs no new dependency
 * for a payload this simple (an array of objects, only the {@code name}
 * field is read).</p>
 */
public final class UpdateChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // HttpClient is explicitly designed to be created once and reused - it owns a connection
    // pool internally, so a fresh instance per check would rebuild that pool (and redo the TLS
    // handshake) every time instead of reusing a warm connection to api.github.com.
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private UpdateChecker() {}

    /**
     * @return the latest tag's version (leading {@code v} stripped) if it's
     * newer than {@code currentVersion}, per {@link VersionComparator};
     * empty if not, or if the check couldn't complete for any reason (no
     * network, rate-limited, malformed response, ...) - an update check
     * failing must never be treated as "no update" being wrong, nor crash
     * the caller.
     */
    public static Optional<String> latestVersionIfNewer(String owner, String repo, String currentVersion, Logger logger) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/tags"))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.log(Level.FINE, "Update check: GitHub returned HTTP {0}", response.statusCode());
                return Optional.empty();
            }

            String latest = highestTag(response.body());
            if (latest != null && VersionComparator.compare(latest, currentVersion) > 0) {
                return Optional.of(latest);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.log(Level.FINE, "Update check failed", e);
            return Optional.empty();
        }
    }

    private static String highestTag(String tagsJson) {
        Object parsed = new Yaml().load(tagsJson);
        if (!(parsed instanceof List<?> tags)) return null;

        String highest = null;
        for (Object entry : tags) {
            if (!(entry instanceof Map<?, ?> tag)) continue;
            Object name = tag.get("name");
            if (!(name instanceof String tagName) || tagName.isBlank()) continue;

            String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (highest == null || VersionComparator.compare(version, highest) > 0) {
                highest = version;
            }
        }
        return highest;
    }
}
