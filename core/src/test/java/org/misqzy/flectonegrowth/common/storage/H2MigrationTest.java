package org.misqzy.flectonegrowth.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.misqzy.flectonegrowth.common.domain.StoredPlayerData;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaMigration;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaMigrationRunner;
import org.misqzy.flectonegrowth.common.storage.migration.SchemaMigrations;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class H2MigrationTest {

    private static final String VERSION = "0.2.11";
    private static final String PREFIX = "flectonegrowth";
    private static final String INDEX = "IDX_FLECTONEGROWTH_PLAYER_USERNAME";
    private static final Logger LOG = Logger.getLogger("h2-migration-test");

    @TempDir Path tmp;
    private H2Storage storage;

    @AfterEach
    void close() {
        if (storage != null) storage.close();
    }

    private H2Storage open(File dir) {
        storage = new H2Storage(LOG, dir, VERSION, PREFIX);
        assertTrue(storage.initialize());
        return storage;
    }

    private static Connection connect(File dir) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:" + new File(dir, "data/database").getAbsolutePath() + ";AUTO_SERVER=TRUE");
    }

    private static String query(File dir, String sql) throws SQLException {
        try (Connection c = connect(dir); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append('\n');
            return sb.toString().trim();
        }
    }

    private static String indexName(File dir) throws SQLException {
        return query(dir, "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME='FLECTONEGROWTH_PLAYER' AND INDEX_NAME='" + INDEX + "'");
    }

    private static String usernameType(File dir) throws SQLException {
        return query(dir, "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FLECTONEGROWTH_PLAYER' AND COLUMN_NAME='USERNAME'");
    }

    private File newDir(String name) {
        File f = tmp.resolve(name).toFile();
        assertTrue(f.mkdirs());
        return f;
    }

    // ---- fresh database ----------------------------------------------

    @Test
    void freshDatabaseIsStampedAndHasIndexAndIgnoreCaseColumn() throws Exception {
        File dir = newDir("fresh");
        open(dir);
        assertEquals(VERSION, query(dir, "SELECT version FROM flectonegrowth_schema_version"));
        assertEquals(INDEX, indexName(dir));
        assertTrue(usernameType(dir).toLowerCase().contains("ignorecase"), usernameType(dir));
    }

    @Test
    void reopeningFreshDatabaseKeepsData() {
        File dir = newDir("reopen");
        UUID u = UUID.randomUUID();
        open(dir).upsertUsername(u, "Notch");
        storage.close();
        open(dir);
        assertEquals(u, storage.findUuidByUsername("notch"));
    }

    // ---- username semantics ------------------------------------------

    @Test
    void findUuidByUsernameIsCaseInsensitive() {
        H2Storage h = open(newDir("ci"));
        UUID u = UUID.randomUUID();
        assertTrue(h.upsertUsername(u, "Notch"));
        assertEquals(u, h.findUuidByUsername("notch"));
        assertEquals(u, h.findUuidByUsername("NOTCH"));
        assertEquals(u, h.findUuidByUsername("Notch"));
        assertNull(h.findUuidByUsername("nobody"));
    }

    @Test
    void renameClashClearsTheOldOwnersName() {
        H2Storage h = open(newDir("clash"));
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        assertTrue(h.upsertUsername(u1, "Notch"));
        assertTrue(h.upsertUsername(u2, "NOTCH"));
        assertEquals(u2, h.findUuidByUsername("notch"));
        assertNull(h.getUsername(u1));
        assertEquals("NOTCH", h.getUsername(u2));
    }

    // ---- loadStoredData vs getters -----------------------------------

    @Test
    void loadStoredDataForUnknownPlayerIsEmpty() {
        H2Storage h = open(newDir("empty"));
        assertEquals(StoredPlayerData.EMPTY, h.loadStoredData(UUID.randomUUID(), ""));
    }

    @Test
    void playerRowWithNothingSetHasAllNulls() {
        H2Storage h = open(newDir("rowonly"));
        UUID u = UUID.randomUUID();
        h.upsertUsername(u, "Steve");
        StoredPlayerData sd = h.loadStoredData(u, "");
        assertNull(sd.customScale());
        assertNull(sd.genderKey());
        assertNull(sd.growthSeconds());
        assertNull(sd.playTime());
    }

    @Test
    void loadStoredDataMatchesIndividualGetters() {
        H2Storage h = open(newDir("stored"));
        UUID u = UUID.randomUUID();
        h.upsertUsername(u, "Alex");
        assertTrue(h.setCustomScale(u, 1.5));
        assertTrue(h.setGenderKey(u, "Female"));
        assertTrue(h.setGrowthTimeSeconds(u, 99));
        h.recordJoin(u, "", 1000);
        h.recordJoin(u, "", 2000);

        StoredPlayerData sd = h.loadStoredData(u, "");
        assertEquals(1.5, sd.customScale());
        assertEquals("female", sd.genderKey());
        assertEquals(99L, sd.growthSeconds());
        assertNotNull(sd.playTime());
        assertEquals(1000, sd.playTime().first());
        assertEquals(2000, sd.playTime().last());
        assertEquals(2, sd.playTime().sessions());

        assertEquals(h.getCustomScale(u), sd.customScale());
        assertEquals(h.getGenderKey(u), sd.genderKey());
        assertEquals(h.getGrowthTimeSeconds(u), sd.growthSeconds());
    }

    @Test
    void playtimeBucketsArePerServer() {
        H2Storage h = open(newDir("buckets"));
        UUID u = UUID.randomUUID();
        h.recordJoin(u, "", 1000);
        assertNotNull(h.loadStoredData(u, "").playTime());
        assertNull(h.loadStoredData(u, "lobby").playTime());
        h.recordJoin(u, "lobby", 5000);
        assertEquals(5000, h.loadStoredData(u, "lobby").playTime().first());
        assertEquals(1000, h.loadStoredData(u, "").playTime().first());
    }

    @Test
    void removedScaleReadsAsNullEverywhere() {
        H2Storage h = open(newDir("removed"));
        UUID u = UUID.randomUUID();
        h.upsertUsername(u, "Alex");
        h.setCustomScale(u, 2.0);
        assertEquals(2.0, h.loadStoredData(u, "").customScale());
        assertTrue(h.removeCustomScale(u));
        assertNull(h.loadStoredData(u, "").customScale());
        assertNull(h.getCustomScale(u));
    }

    // ---- 0.2.10 -> 0.2.11 --------------------------------------------

    private File create0210Database(UUID o1, UUID o2) throws SQLException {
        File old = newDir("old");
        new File(old, "data").mkdirs();
        try (Connection c = connect(old); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE flectonegrowth_player (id INT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL UNIQUE, username VARCHAR(16))");
            s.execute("CREATE TABLE flectonegrowth_scales (player_id INT PRIMARY KEY, scale DOUBLE NOT NULL, valid BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY (player_id) REFERENCES flectonegrowth_player(id))");
            s.execute("CREATE TABLE flectonegrowth_genders (player_id INT PRIMARY KEY, gender_key VARCHAR(32) NOT NULL DEFAULT 'male', valid BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY (player_id) REFERENCES flectonegrowth_player(id))");
            s.execute("CREATE TABLE flectonegrowth_growth_time (player_id INT PRIMARY KEY, growth_seconds BIGINT NOT NULL, valid BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY (player_id) REFERENCES flectonegrowth_player(id))");
            s.execute("CREATE TABLE flectonegrowth_schema_version (id INT PRIMARY KEY, version VARCHAR(32) NOT NULL)");
            s.execute("CREATE TABLE flectonegrowth_playtime (player_id INT NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, total_seconds BIGINT NOT NULL DEFAULT 0, sessions INT NOT NULL DEFAULT 0, PRIMARY KEY (player_id, server), FOREIGN KEY (player_id) REFERENCES flectonegrowth_player(id))");
            s.execute("INSERT INTO flectonegrowth_schema_version VALUES (1, '0.2.10')");
            s.execute("INSERT INTO flectonegrowth_player (uuid, username) VALUES ('" + o1 + "', 'MixedCase')");
            s.execute("INSERT INTO flectonegrowth_player (uuid, username) VALUES ('" + o2 + "', NULL)");
            s.execute("INSERT INTO flectonegrowth_scales VALUES (1, 2.0, TRUE)");
        }
        return old;
    }

    @Test
    void migratingA0210DatabaseKeepsDataAndAddsIndexAndIgnoreCase() throws Exception {
        UUID o1 = UUID.randomUUID(), o2 = UUID.randomUUID();
        File old = create0210Database(o1, o2);
        assertEquals("", indexName(old));
        assertFalse(usernameType(old).toLowerCase().contains("ignorecase"));

        H2Storage h = open(old);

        assertEquals(VERSION, query(old, "SELECT version FROM flectonegrowth_schema_version"));
        assertEquals(INDEX, indexName(old));
        assertTrue(usernameType(old).toLowerCase().contains("ignorecase"), usernameType(old));
        assertEquals(o1, h.findUuidByUsername("mixedcase"));
        assertEquals(o1, h.findUuidByUsername("MIXEDCASE"));
        assertEquals(2.0, h.getCustomScale(o1));
        assertNull(h.getUsername(o2));
        String plan = query(old, "EXPLAIN SELECT uuid FROM flectonegrowth_player WHERE username = 'x'");
        assertTrue(plan.toUpperCase().contains(INDEX), plan);
    }

    @Test
    void reopeningAMigratedDatabaseIsANoOp() throws Exception {
        UUID o1 = UUID.randomUUID(), o2 = UUID.randomUUID();
        File old = create0210Database(o1, o2);
        open(old);
        storage.close();
        open(old);
        assertEquals(o1, storage.findUuidByUsername("MixedCase"));
        assertEquals(VERSION, query(old, "SELECT version FROM flectonegrowth_schema_version"));
    }

    @Test
    void runningTheStepTwiceDirectlyIsIdempotent() throws Exception {
        UUID o1 = UUID.randomUUID(), o2 = UUID.randomUUID();
        File old = create0210Database(o1, o2);
        open(old);
        storage.close();

        List<SchemaMigration> steps = SchemaMigrations.pending("0.2.10", VERSION);
        assertEquals(1, steps.size());
        List<String> sql = steps.get(0).statements(StorageType.H2, new TableNames(PREFIX));
        try (Connection c = connect(old)) {
            assertDoesNotThrow(() -> SchemaMigrationRunner.run(c, sql));
            assertDoesNotThrow(() -> SchemaMigrationRunner.run(c, sql));
        }
        assertEquals(INDEX, indexName(old));
        open(old);
        assertEquals(o1, storage.findUuidByUsername("mixedcase"));
    }

    @Test
    void nothingIsPendingFromTheCurrentVersion() {
        assertTrue(SchemaMigrations.pending(VERSION, VERSION).isEmpty());
        assertTrue(SchemaMigrations.pending("0.3.0", "0.3.0").isEmpty());
    }

    @Test
    void mysqlStatementsAreProducedForTheLatestStep() {
        List<SchemaMigration> steps = SchemaMigrations.pending("0.2.10", VERSION);
        List<String> sql = steps.get(0).statements(StorageType.MYSQL, new TableNames(PREFIX));
        assertFalse(sql.isEmpty());
        assertTrue(sql.stream().anyMatch(s -> s.toUpperCase().contains("CREATE INDEX")), sql.toString());
        assertTrue(steps.get(0).statements(StorageType.YAML, new TableNames(PREFIX)).isEmpty());
    }
}
