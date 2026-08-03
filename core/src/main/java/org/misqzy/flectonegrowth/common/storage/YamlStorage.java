package org.misqzy.flectonegrowth.common.storage;

import org.misqzy.flectonegrowth.common.domain.PlayTime;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flat-file storage using plain SnakeYAML (no Bukkit {@code FileConfiguration}
 * dependency), so it can run unmodified on any platform module. A
 * {@link ReadWriteLock} keeps concurrent async reads/writes safe, same as
 * the original implementation.
 */
public final class YamlStorage implements Storage {

    private final Logger logger;
    private final File file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Yaml yaml;

    @SuppressWarnings("unchecked")
    private Map<String, Object> root = new HashMap<>();

    public YamlStorage(Logger logger, File dataFolder) {
        this.logger = logger;
        this.file = new File(new File(dataFolder, "data"), "player_data.yml");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean initialize() {
        try {
            File parent = file.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();

            if (!file.exists()) {
                if (!file.createNewFile() && !file.exists()) {
                    logger.severe("Failed to initialise YAML storage: could not create " + file.getAbsolutePath());
                    return false;
                }
            } else {
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    Object loaded = yaml.load(reader);
                    if (loaded instanceof Map<?, ?> m) root = (Map<String, Object>) m;
                }
            }
            section("scales");
            section("genders");
            section("growth-times");
            section("playtimes");
            section("players");
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to initialise YAML storage: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean testConnection() {
        return file.exists() && file.canWrite();
    }

    @Override public StorageType type() { return StorageType.YAML; }

    @Override public void close() { /* flat file - nothing to close */ }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) {
        return (Map<String, Object>) root.computeIfAbsent(name, k -> new HashMap<String, Object>());
    }

    /**
     * Writes to a sibling temp file and atomically renames it over {@link #file},
     * so a crash/power-loss mid-write can never leave a truncated or half-written
     * {@code player_data.yml} behind - the original stays intact until the new
     * content is fully flushed to disk.
     */
    private void persist() throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(tmp.toPath()), StandardCharsets.UTF_8)) {
                yaml.dump(root, writer);
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Don't leave a half-written/orphaned .tmp file behind for a failed dump or a move
            // that can't complete (e.g. AtomicMoveNotSupportedException) - the next successful
            // persist() would overwrite it anyway, but there's no reason to leave it dangling
            // in the meantime if this call is about to report failure to its own caller.
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // Custom scale
    // -----------------------------------------------------------------------

    @Override
    public Double getCustomScale(UUID uuid) {
        lock.readLock().lock();
        try {
            Map<?, ?> entry = entry(section("scales"), uuid);
            if (entry == null || !Boolean.TRUE.equals(entry.get("valid"))) return null;
            Object v = entry.get("value");
            return v instanceof Number n ? n.doubleValue() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean setCustomScale(UUID uuid, double scale) {
        lock.writeLock().lock();
        try {
            Map<String, Object> entry = newEntry();
            entry.put("value", scale);
            entry.put("valid", true);
            section("scales").put(uuid.toString(), entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML setCustomScale: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeCustomScale(UUID uuid) {
        lock.writeLock().lock();
        try {
            Map<?, ?> entry = entry(section("scales"), uuid);
            if (entry == null) return true;
            ((Map<String, Object>) entry).put("valid", false);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML removeCustomScale: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Gender
    // -----------------------------------------------------------------------

    @Override
    public String getGenderKey(UUID uuid) {
        lock.readLock().lock();
        try {
            Map<?, ?> entry = entry(section("genders"), uuid);
            if (entry == null || !Boolean.TRUE.equals(entry.get("valid"))) return null;
            Object v = entry.get("value");
            return v != null ? String.valueOf(v) : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean setGenderKey(UUID uuid, String genderKey) {
        lock.writeLock().lock();
        try {
            Map<String, Object> entry = newEntry();
            entry.put("value", genderKey.toLowerCase(Locale.ROOT));
            entry.put("valid", true);
            section("genders").put(uuid.toString(), entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML setGenderKey: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Growth time
    // -----------------------------------------------------------------------

    @Override
    public Long getGrowthTimeSeconds(UUID uuid) {
        lock.readLock().lock();
        try {
            Map<?, ?> entry = entry(section("growth-times"), uuid);
            if (entry == null || !Boolean.TRUE.equals(entry.get("valid"))) return null;
            Object v = entry.get("value");
            return v instanceof Number n ? n.longValue() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean setGrowthTimeSeconds(UUID uuid, long seconds) {
        lock.writeLock().lock();
        try {
            Map<String, Object> entry = newEntry();
            entry.put("value", seconds);
            entry.put("valid", true);
            section("growth-times").put(uuid.toString(), entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML setGrowthTimeSeconds: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Playtime tracking
    // -----------------------------------------------------------------------

    @Override
    public PlayTime getPlayTime(UUID uuid, String server) {
        lock.readLock().lock();
        try {
            Map<?, ?> entry = entry(section("playtimes"), playtimeKey(uuid, server));
            if (entry == null) return null;
            return new PlayTime(longOf(entry, "first"), longOf(entry, "last"), longOf(entry, "total"), (int) longOf(entry, "sessions"));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads the existing entry (if any) to preserve {@code first}/{@code total}
     * and bump {@code sessions} - the same get-or-create shape H2's
     * {@code recordJoin} uses, since a flat-file "insert or partial update" has
     * no atomic single-write equivalent either.
     */
    @Override
    public boolean recordJoin(UUID uuid, String server, long nowEpochSeconds) {
        lock.writeLock().lock();
        try {
            String key = playtimeKey(uuid, server);
            Map<?, ?> existing = entry(section("playtimes"), key);

            Map<String, Object> entry = newEntry();
            entry.put("first", existing != null ? longOf(existing, "first") : nowEpochSeconds);
            entry.put("last", nowEpochSeconds);
            entry.put("total", existing != null ? longOf(existing, "total") : 0L);
            entry.put("sessions", existing != null ? (int) longOf(existing, "sessions") + 1 : 1);
            section("playtimes").put(key, entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML recordJoin: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean checkpointPlayTime(UUID uuid, String server, long totalSeconds, long nowEpochSeconds) {
        lock.writeLock().lock();
        try {
            String key = playtimeKey(uuid, server);
            Map<?, ?> existing = entry(section("playtimes"), key);

            Map<String, Object> entry = newEntry();
            entry.put("first", existing != null ? longOf(existing, "first") : nowEpochSeconds);
            entry.put("last", nowEpochSeconds);
            entry.put("total", totalSeconds);
            entry.put("sessions", existing != null ? (int) longOf(existing, "sessions") : 0);
            section("playtimes").put(key, entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML checkpointPlayTime: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Same read-then-carry-forward shape as {@link #recordJoin} - adds to the existing total instead of overwriting it. */
    @Override
    public boolean addPlayTime(UUID uuid, String server, long secondsToAdd, long nowEpochSeconds) {
        lock.writeLock().lock();
        try {
            String key = playtimeKey(uuid, server);
            Map<?, ?> existing = entry(section("playtimes"), key);

            Map<String, Object> entry = newEntry();
            entry.put("first", existing != null ? longOf(existing, "first") : nowEpochSeconds);
            entry.put("last", nowEpochSeconds);
            entry.put("total", (existing != null ? longOf(existing, "total") : 0L) + secondsToAdd);
            entry.put("sessions", existing != null ? (int) longOf(existing, "sessions") : 0);
            section("playtimes").put(key, entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML addPlayTime: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Player identity (no surrogate-id/FK model here - a flat file has no
    // join cost to optimise away, so this is just a uuid-keyed username map
    // alongside the sections above)
    // -----------------------------------------------------------------------

    @Override
    public boolean upsertUsername(UUID uuid, String username) {
        lock.writeLock().lock();
        try {
            Map<String, Object> entry = newEntry();
            entry.put("username", username);
            section("players").put(uuid.toString(), entry);
            persist();
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "YAML upsertUsername: " + e.getMessage(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getUsername(UUID uuid) {
        lock.readLock().lock();
        try {
            Map<?, ?> entry = entry(section("players"), uuid);
            Object v = entry != null ? entry.get("username") : null;
            return v != null ? String.valueOf(v) : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Linear scan - fine here, YAML storage is explicitly single-server/small-scale (see class javadoc). */
    @Override
    public UUID findUuidByUsername(String username) {
        lock.readLock().lock();
        try {
            for (Map.Entry<String, Object> e : section("players").entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> entry)) continue;
                Object v = entry.get("username");
                if (v != null && String.valueOf(v).equalsIgnoreCase(username)) return UUID.fromString(e.getKey());
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@code uuid} alone for the shared network-wide bucket ({@code server} == "", also matches every pre-existing row on disk) - {@code uuid:server} for a per-server bucket, so switching network.per-server on doesn't collide with the old key shape. */
    private String playtimeKey(UUID uuid, String server) {
        return server.isEmpty() ? uuid.toString() : uuid + ":" + server;
    }

    private long longOf(Map<?, ?> entry, String key) {
        Object v = entry.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // -----------------------------------------------------------------------

    private Map<?, ?> entry(Map<String, Object> section, UUID uuid) {
        return entry(section, uuid.toString());
    }

    private Map<?, ?> entry(Map<String, Object> section, String key) {
        Object v = section.get(key);
        return v instanceof Map<?, ?> m ? m : null;
    }

    private Map<String, Object> newEntry() {
        return new HashMap<>();
    }
}
