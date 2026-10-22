package id.rexz.webtoonlite;

/** Rate limit + backoff for Gemini calls (offline, quota). Time is passed in so it is unit-testable. */
final class ApiGate {
    static final long MIN_INTERVAL_MS = 2000;
    static final long QUOTA_BLOCK_MS = 60_000;
    static final long OFFLINE_BASE_MS = 4000, OFFLINE_MAX_MS = 30_000;

    private long blockedUntil;
    private int failures;

    /** Milliseconds to wait before the next request may start (0 = go now). */
    long waitMs(long now, long lastSentAt) {
        long byInterval = lastSentAt == 0 ? 0 : (lastSentAt + MIN_INTERVAL_MS) - now;
        return Math.max(0, Math.max(byInterval, blockedUntil - now));
    }

    void onSuccess() { failures = 0; blockedUntil = 0; }

    void onQuotaExceeded(long now) { blockedUntil = now + QUOTA_BLOCK_MS; }

    /** Offline / timeout: 4 s, 8 s, 16 s, 30 s ... */
    void onOffline(long now) {
        failures = Math.min(failures + 1, 6);
        blockedUntil = now + Math.min(OFFLINE_MAX_MS, OFFLINE_BASE_MS << (failures - 1));
    }

    /** Any other failure (server error, bad answer): short backoff so we never hammer the API. */
    void onError(long now) {
        failures = Math.min(failures + 1, 6);
        blockedUntil = now + Math.min(15_000, 3000L * failures);
    }

    boolean isBlocked(long now) { return now < blockedUntil; }
}
