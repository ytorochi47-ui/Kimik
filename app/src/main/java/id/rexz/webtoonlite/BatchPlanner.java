package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Decides which dialogs go into the next Gemini batch: new, not cached, not already in flight. Pure Java. */
final class BatchPlanner {
    static final class Item {
        final int id; final String key; final String text;
        Item(int id, String key, String text) { this.id = id; this.key = key; this.text = text; }
    }

    private BatchPlanner() { }

    /**
     * @param known   true when the key already has a translation (or a "skip" marker) in the cache
     * @param inFlight keys of a request that is already running
     */
    static List<Item> plan(List<Dialog> dialogs, Predicate<String> known, Set<String> inFlight, int maxItems, int maxChars) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int chars = 0;
        for (Dialog d : dialogs) {
            if (out.size() >= maxItems) break;
            if (d.key.isEmpty() || known.test(d.key) || inFlight.contains(d.key) || !seen.add(d.key)) continue;
            String text = d.text.length() > 400 ? d.text.substring(0, 400) : d.text;
            if (!out.isEmpty() && chars + text.length() > maxChars) break;
            chars += text.length();
            out.add(new Item(out.size() + 1, d.key, text));
        }
        return out;
    }
}
