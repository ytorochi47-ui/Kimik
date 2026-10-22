package id.rexz.webtoonlite;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Gemini's daily quota resets at midnight Pacific time. Pure Java (java.time, API 26+). */
final class QuotaClock {
    static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    private QuotaClock() { }

    static String dayKey(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(PACIFIC).toLocalDate().toString();
    }

    static long nextResetMs(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(PACIFIC).toLocalDate().plusDays(1)
                .atStartOfDay(PACIFIC).toInstant().toEpochMilli();
    }

    static String clock(long ms, ZoneId zone) {
        return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone));
    }

    /** "34s", "34.5s" or "1m" style delays from google.rpc.RetryInfo -> milliseconds (0 when unknown). */
    static long parseRetryDelayMs(String s) {
        if (s == null) return 0;
        String t = s.trim().toLowerCase();
        try {
            if (t.endsWith("ms")) return (long) Double.parseDouble(t.substring(0, t.length() - 2));
            if (t.endsWith("s")) return (long) (Double.parseDouble(t.substring(0, t.length() - 1)) * 1000);
            if (t.endsWith("m")) return (long) (Double.parseDouble(t.substring(0, t.length() - 1)) * 60_000);
        } catch (NumberFormatException ignored) { }
        return 0;
    }
}
