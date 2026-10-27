package org.misqzy.flectonegrowth.common.log;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console-friendly failure logging: one concise line per failure ("operation failed: RootCause: message"),
 * with the full stack trace only at {@link Level#FINE}. Identical failures (same operation and same root
 * cause) are rate-limited to one line per minute and report how many were suppressed, so a database outage
 * produces a single recurring line instead of one multi-screen trace per player.
 *
 * <p>Bukkit's logger already prefixes every line with the plugin name - never repeat it in messages.</p>
 */
public final class PluginLog {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED = 256;
    private static final int MAX_MESSAGE = 200;

    private static final class State {
        long lastLogged;
        int suppressed;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private PluginLog() {}

    /** Logs {@code operation + " failed: " + cause} at SEVERE (rate-limited). */
    public static void error(Logger logger, String operation, Throwable t) {
        log(logger, Level.SEVERE, operation, t);
    }

    /** Logs {@code operation + " failed: " + cause} at WARNING (rate-limited). */
    public static void warn(Logger logger, String operation, Throwable t) {
        log(logger, Level.WARNING, operation, t);
    }

    /** One-line description of the root cause: {@code SimpleName: first line of message}. */
    public static String describe(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 16 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        String msg = root.getMessage();
        String head = root.getClass().getSimpleName();
        if (msg != null && !msg.isBlank()) {
            String line = msg.strip();
            int nl = line.indexOf('\n');
            if (nl >= 0) line = line.substring(0, nl).strip();
            if (line.length() > MAX_MESSAGE) line = line.substring(0, MAX_MESSAGE) + "...";
            head += ": " + line;
        }
        // Programming errors (not I/O or SQL failures) are useless without a location.
        if (!(root instanceof java.sql.SQLException) && !(root instanceof java.io.IOException)) {
            StackTraceElement[] trace = root.getStackTrace();
            if (trace.length > 0) head += " (at " + trace[0] + ")";
        }
        return head;
    }

    private static void log(Logger logger, Level level, String operation, Throwable t) {
        String cause = describe(t);
        String key = operation + '|' + cause;
        long now = System.currentTimeMillis();

        int repeated = 0;
        boolean emit;
        if (STATES.size() > MAX_TRACKED) prune(now);
        State s = STATES.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            if (s.lastLogged != 0 && now - s.lastLogged < WINDOW_MS) {
                s.suppressed++;
                emit = false;
            } else {
                repeated = s.suppressed;
                s.suppressed = 0;
                s.lastLogged = now;
                emit = true;
            }
        }

        if (logger.isLoggable(Level.FINE)) logger.log(Level.FINE, operation + " failed", t);
        if (!emit) return;
        String suffix = repeated > 0 ? " (repeated " + repeated + " times)" : "";
        logger.log(level, operation + " failed: " + cause + suffix);
    }

    private static void prune(long now) {
        Iterator<Map.Entry<String, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            State s = it.next().getValue();
            synchronized (s) {
                if (now - s.lastLogged >= WINDOW_MS && s.suppressed == 0) it.remove();
            }
        }
    }
}
