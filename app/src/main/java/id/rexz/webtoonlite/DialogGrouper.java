package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups OCR lines (tight boxes) into dialogs. Two steps:
 * 1) lines on the same row (a detector that split a sentence into words) are merged;
 * 2) rows stacked closely, overlapping horizontally and of similar height form one dialog.
 * Pure Java.
 */
final class DialogGrouper {
    private DialogGrouper() { }

    private static final class Row {
        int l, t, r, b; int h; String text; int members;
        float conf;
    }

    static List<Dialog> group(List<TextBox> in, int maxDialogs) {
        List<TextBox> boxes = new ArrayList<>();
        for (TextBox b : in) if (b.width() > 0 && b.height() > 0 && b.text != null && !b.text.trim().isEmpty()) boxes.add(b);
        int n = boxes.size();
        if (n == 0) return new ArrayList<>();

        // ---- 1) rows
        int[] p = identity(n);
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (sameRow(boxes.get(i), boxes.get(j))) union(p, i, j);
        List<Row> rows = new ArrayList<>();
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int root = find(p, i);
            if (seen[root]) continue;
            seen[root] = true;
            List<TextBox> members = new ArrayList<>();
            for (int j = 0; j < n; j++) if (find(p, j) == root) members.add(boxes.get(j));
            Collections.sort(members, new Comparator<TextBox>() {
                @Override public int compare(TextBox a, TextBox b) { return Integer.compare(a.left, b.left); }
            });
            Row row = new Row();
            row.l = Integer.MAX_VALUE; row.t = Integer.MAX_VALUE; row.r = Integer.MIN_VALUE; row.b = Integer.MIN_VALUE;
            StringBuilder sb = new StringBuilder();
            long hSum = 0; double confSum = 0;
            for (TextBox m : members) {
                row.l = Math.min(row.l, m.left); row.t = Math.min(row.t, m.top);
                row.r = Math.max(row.r, m.right); row.b = Math.max(row.b, m.bottom);
                if (sb.length() > 0) sb.append(' ');
                sb.append(m.text.trim());
                hSum += m.height(); confSum += m.conf;
            }
            row.h = (int) (hSum / members.size());
            row.text = sb.toString();
            row.members = members.size();
            row.conf = (float) (confSum / members.size());
            rows.add(row);
        }

        // ---- 2) dialogs
        int m = rows.size();
        int[] q = identity(m);
        for (int i = 0; i < m; i++)
            for (int j = i + 1; j < m; j++)
                if (sameDialog(rows.get(i), rows.get(j))) union(q, i, j);
        List<Dialog> out = new ArrayList<>();
        boolean[] done = new boolean[m];
        for (int i = 0; i < m; i++) {
            int root = find(q, i);
            if (done[root]) continue;
            done[root] = true;
            List<Row> members = new ArrayList<>();
            for (int j = 0; j < m; j++) if (find(q, j) == root) members.add(rows.get(j));
            Collections.sort(members, new Comparator<Row>() {
                @Override public int compare(Row a, Row b) { return a.t != b.t ? Integer.compare(a.t, b.t) : Integer.compare(a.l, b.l); }
            });
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
            StringBuilder sb = new StringBuilder();
            List<Integer> hs = new ArrayList<>();
            double confSum = 0; int cw = 0;
            for (Row row : members) {
                l = Math.min(l, row.l); t = Math.min(t, row.t); r = Math.max(r, row.r); b = Math.max(b, row.b);
                if (sb.length() > 0) sb.append(' ');
                sb.append(row.text);
                hs.add(row.h);
                confSum += row.conf * row.members; cw += row.members;
            }
            Collections.sort(hs);
            out.add(new Dialog(l, t, r, b, sb.toString(), members.size(), hs.get(hs.size() / 2), (float) (confSum / Math.max(1, cw))));
        }
        Collections.sort(out, new Comparator<Dialog>() {
            @Override public int compare(Dialog a, Dialog b) { return a.top != b.top ? Integer.compare(a.top, b.top) : Integer.compare(a.left, b.left); }
        });
        if (out.size() > maxDialogs) return new ArrayList<>(out.subList(0, maxDialogs));
        return out;
    }

    static boolean sameRow(TextBox a, TextBox b) {
        int minH = Math.min(a.height(), b.height());
        int maxH = Math.max(a.height(), b.height());
        if (maxH > minH * 1.8f) return false;                              // different type size
        if (Math.abs(a.centerY() - b.centerY()) > 0.35f * minH) return false;
        int gapX = Math.max(a.left, b.left) - Math.min(a.right, b.right);   // negative = overlap
        return gapX <= 1.5f * minH;
    }

    private static boolean sameDialog(Row a, Row b) {
        int minH = Math.min(a.h, b.h), maxH = Math.max(a.h, b.h);
        if (maxH > minH * 1.5f) return false;
        int gapY = Math.max(a.t, b.t) - Math.min(a.b, b.b);                  // negative = overlap
        if (gapY > 0.9f * minH) return false;
        int overlapX = Math.min(a.r, b.r) - Math.max(a.l, b.l);
        int minW = Math.min(a.r - a.l, b.r - b.l);
        return overlapX >= 0.3f * minW;
    }

    private static int[] identity(int n) { int[] p = new int[n]; for (int i = 0; i < n; i++) p[i] = i; return p; }
    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
    private static void union(int[] p, int a, int b) { int ra = find(p, a), rb = find(p, b); if (ra != rb) p[ra] = rb; }
}
