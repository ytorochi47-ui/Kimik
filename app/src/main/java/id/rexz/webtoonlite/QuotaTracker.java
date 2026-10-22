package id.rexz.webtoonlite;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Counts Gemini requests this app sent today (Pacific day, like Google's quota). It does not know about
 * other apps using the same key, so it is only a guard rail against burning the free daily budget.
 */
final class QuotaTracker {
    /** Free tier of Gemini 3.5 Flash Lite (AI Studio): 15 requests/minute, 500 requests/day. */
    static final int DAILY_LIMIT = 500;
    /** Realtime stops sending here and leaves the rest for manual T / camera translations. */
    static final int REALTIME_CEILING = 440;
    private static final String PREFS = "webtoonlite_quota";

    private QuotaTracker() { }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static synchronized int used(Context c) {
        SharedPreferences p = prefs(c);
        return QuotaClock.dayKey(System.currentTimeMillis()).equals(p.getString("day", "")) ? p.getInt("count", 0) : 0;
    }

    static synchronized int record(Context c) {
        SharedPreferences p = prefs(c);
        String today = QuotaClock.dayKey(System.currentTimeMillis());
        int n = (today.equals(p.getString("day", "")) ? p.getInt("count", 0) : 0) + 1;
        p.edit().putString("day", today).putInt("count", n).apply();
        return n;
    }
}
