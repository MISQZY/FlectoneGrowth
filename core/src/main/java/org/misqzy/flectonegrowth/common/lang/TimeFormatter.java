package org.misqzy.flectonegrowth.common.lang;

import java.util.Locale;

public final class TimeFormatter {

    private TimeFormatter() {}

    /** A short wall-clock duration as {@code x.nnnUOM}, e.g. {@code 0.042s} for 42ms - always seconds, so admin-facing timings (like a reload) read as one consistent decimal instead of jumping between "512ms" and "1.3s". */
    public static String formatMillis(long millis) {
        return String.format(Locale.US, "%.3fs", millis / 1000.0);
    }

    /** Renders in {@code messages}' configured default locale - see {@link #format(long, Messages, String)} for a specific recipient's own locale. */
    public static String format(long totalSeconds, Messages messages) {
        return format(totalSeconds, messages, null);
    }

    /** @param locale a specific recipient's locale (e.g. a player's client locale), or {@code null} for {@code messages}' configured default - see {@link Messages#raw(String, String)}. */
    public static String format(long totalSeconds, Messages messages, String locale) {
        if (totalSeconds <= 0) return "0 " + messages.raw(locale, "time.seconds");

        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) append(sb, days, messages.raw(locale, "time.days"));
        if (hours > 0) append(sb, hours, messages.raw(locale, "time.hours"));
        if (minutes > 0) append(sb, minutes, messages.raw(locale, "time.mins"));
        if (seconds > 0 || sb.isEmpty()) append(sb, seconds, messages.raw(locale, "time.seconds"));
        return sb.toString();
    }

    private static void append(StringBuilder sb, long value, String label) {
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(value).append(' ').append(label);
    }
}
