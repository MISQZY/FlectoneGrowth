package org.misqzy.flectonegrowth.common.log;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginLog reads {@code System.currentTimeMillis()} directly, so the one-minute window can't be waited out.
 * The first part (suppression inside the window) is naturally deterministic; for the "(repeated N times)"
 * report the test rewinds the private per-key state's timestamp by reflection instead of sleeping.
 */
class PluginLogTest {

    private static final class Capture extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        @Override public void publish(LogRecord r) { records.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private static Logger logger(Capture cap) {
        Logger l = Logger.getLogger("pluginlog-test-" + UUID.randomUUID());
        l.setUseParentHandlers(false);
        l.setLevel(Level.INFO);
        l.addHandler(cap);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static void rewind(String operation, Throwable t) throws Exception {
        Field f = PluginLog.class.getDeclaredField("STATES");
        f.setAccessible(true);
        Map<String, Object> states = (Map<String, Object>) f.get(null);
        Object state = states.get(operation + '|' + PluginLog.describe(t));
        assertNotNull(state, "state for key not tracked");
        Field last = state.getClass().getDeclaredField("lastLogged");
        last.setAccessible(true);
        last.setLong(state, System.currentTimeMillis() - 61_000L);
    }

    @Test
    void identicalFailuresWithinWindowAreSuppressedThenReported() throws Exception {
        Capture cap = new Capture();
        Logger log = logger(cap);
        String op = "load-" + UUID.randomUUID();
        Throwable t = new java.sql.SQLException("connection refused");

        PluginLog.error(log, op, t);
        PluginLog.error(log, op, t);
        PluginLog.error(log, op, t);
        assertEquals(1, cap.records.size());
        assertEquals(Level.SEVERE, cap.records.get(0).getLevel());
        assertEquals(op + " failed: SQLException: connection refused", cap.records.get(0).getMessage());

        rewind(op, t);
        PluginLog.error(log, op, t);
        assertEquals(2, cap.records.size());
        assertEquals(op + " failed: SQLException: connection refused (repeated 2 times)", cap.records.get(1).getMessage());

        // counter was reset: next window starts clean
        rewind(op, t);
        PluginLog.error(log, op, t);
        assertEquals(op + " failed: SQLException: connection refused", cap.records.get(2).getMessage());
    }

    @Test
    void differentOperationOrCauseIsLoggedIndependently() {
        Capture cap = new Capture();
        Logger log = logger(cap);
        String op = "save-" + UUID.randomUUID();
        PluginLog.warn(log, op, new java.io.IOException("disk full"));
        PluginLog.warn(log, op, new java.io.IOException("other"));
        PluginLog.warn(log, op + "-2", new java.io.IOException("disk full"));
        assertEquals(3, cap.records.size());
        assertEquals(Level.WARNING, cap.records.get(0).getLevel());
    }

    @Test
    void describeUsesRootCauseFirstLineAndTruncates() {
        Throwable t = new RuntimeException("wrapper", new java.sql.SQLException("first line\nsecond line"));
        assertEquals("SQLException: first line", PluginLog.describe(t));
        String longMsg = "x".repeat(500);
        String d = PluginLog.describe(new java.io.IOException(longMsg));
        assertEquals("IOException: " + "x".repeat(200) + "...", d);
        assertEquals("SQLException", PluginLog.describe(new java.sql.SQLException()));
    }

    @Test
    void programmingErrorsGetALocation() {
        String d = PluginLog.describe(new IllegalStateException("boom"));
        assertTrue(d.startsWith("IllegalStateException: boom (at "), d);
    }
}
