package id.rexz.webtoonlite;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** API calls happen only when the user selects a region. Never embed an API key in the APK. */
public final class GeminiTranslator {
    public static final String MODEL = "gemini-3.5-flash-lite";
    private GeminiTranslator() { }

    private static final String SYSTEM = "Kamu penerjemah webtoon Inggris ke Indonesia. " +
            "Terjemahkan teks dialog secara utuh: natural, mengalir, sesuai emosi dan karakter, gaya percakapan komik. " +
            "Pertahankan nama, istilah, humor, dan nada; jangan mengarang gender, relasi, atau alur. " +
            "Teks berasal dari OCR dan mungkin ada huruf keliru; koreksi hanya bila jelas. " +
            "Jawab HANYA terjemahannya.";

    /** Sends text only (never an image). {@code history} may be empty. */
    public static String translate(String apiKey, String text, String history) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Dialog belum terbaca.");
        String clean = limit(text.trim(), 1500);
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.trim().isEmpty())
            prompt.append("Dialog sebelumnya (hanya untuk konteks, tidak berurutan):\n")
                  .append(limit(history.trim(), 700)).append("\n\n");
        prompt.append("Terjemahkan:\n").append(clean);
        // Headroom matters: on thinking-capable models, reasoning tokens count against this cap.
        int maxTokens = Math.max(300, Math.min(800, clean.length() + 200));

        JSONObject request = new JSONObject();
        request.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))));
        request.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt.toString())))));
        request.put("generationConfig", new JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens));
        return post(apiKey, request, null);
    }

    // ------------------------------------------------------------------ realtime batch (text only)

    private static final String BATCH_SYSTEM = SYSTEM.replace("Jawab HANYA terjemahannya.", "") +
            "Input adalah JSON array berisi objek {\"id\":angka,\"s\":teks Inggris}. " +
            "Jawab HANYA JSON array berisi objek {\"id\":angka,\"t\":terjemahan Indonesia} untuk setiap id, tanpa teks lain. " +
            "Teks bisa tersambung dengan dialog lain di layar yang sama; jaga kesinambungan nada. " +
            "Jika teks jelas bukan dialog/narasi komik (URL, menu aplikasi atau browser, jam, angka, watermark), isi t dengan string kosong.";

    /** Thrown for HTTP 429 so callers can back off instead of retrying. */
    public static final class QuotaExceededException extends Exception {
        /** How long Google asked us to wait (0 = unknown) and whether it is the per-day limit. */
        final long retryAfterMs; final boolean daily;
        QuotaExceededException(String m, long retryAfterMs, boolean daily) { super(m); this.retryAfterMs = retryAfterMs; this.daily = daily; }
    }

    /** Turns a 429 body into a specific, actionable exception (per-minute vs per-day limit). */
    static QuotaExceededException quotaError(String response, String fallbackMsg) {
        long retryMs = 0; boolean daily = false;
        try {
            JSONObject err = new JSONObject(response).getJSONObject("error");
            JSONArray details = err.optJSONArray("details");
            for (int i = 0; details != null && i < details.length(); i++) {
                JSONObject d = details.getJSONObject(i);
                String type = d.optString("@type", "");
                if (type.endsWith("RetryInfo")) retryMs = QuotaClock.parseRetryDelayMs(d.optString("retryDelay", ""));
                JSONArray violations = d.optJSONArray("violations");
                for (int j = 0; violations != null && j < violations.length(); j++) {
                    String id = violations.getJSONObject(j).optString("quotaId", "") + violations.getJSONObject(j).optString("quotaMetric", "");
                    if (id.toLowerCase().contains("perday")) daily = true;
                }
            }
            if (err.optString("message", "").toLowerCase().contains("per day")) daily = true;
        } catch (Exception ignored) { }
        long now = System.currentTimeMillis();
        String msg;
        if (daily) {
            msg = "Kuota harian Gemini habis (batas gratis ±" + QuotaTracker.DAILY_LIMIT + " permintaan/hari). Reset sekitar "
                    + QuotaClock.clock(QuotaClock.nextResetMs(now), java.time.ZoneId.systemDefault()) + ".";
        } else {
            long secs = Math.max(10, (retryMs + 999) / 1000);
            msg = "Batas per menit Gemini tercapai (gratis: 15/menit). Coba lagi sekitar " + secs + " detik.";
        }
        return new QuotaExceededException(msg, retryMs, daily);
    }

    /** Thrown when {@link CancelToken#cancel()} aborted the request. */
    public static final class CancelledException extends Exception {
        CancelledException() { super("Dibatalkan."); }
    }

    /** Lets a stale request be aborted from another thread (the connection is closed). */
    public static final class CancelToken {
        private volatile boolean cancelled;
        private volatile HttpURLConnection conn;
        public void cancel() { cancelled = true; HttpURLConnection c = conn; if (c != null) c.disconnect(); }
        public boolean isCancelled() { return cancelled; }
        void attach(HttpURLConnection c) { conn = c; if (cancelled) c.disconnect(); }
    }

    /**
     * Translates several dialogs in ONE text-only request. {@code ids} and {@code texts} are parallel.
     * Returns id -> Indonesian text ("" means "not a dialog, leave it alone"); ids the model skipped are absent.
     */
    public static Map<Integer, String> translateBatch(String apiKey, List<Integer> ids, List<String> texts,
                                                      String history, CancelToken token) throws Exception {
        if (ids.isEmpty() || ids.size() != texts.size()) throw new IllegalArgumentException("Batch kosong.");
        JSONArray input = new JSONArray();
        int chars = 0;
        for (int i = 0; i < ids.size(); i++) {
            String t = limit(texts.get(i).trim(), 400);
            chars += t.length();
            input.put(new JSONObject().put("id", ids.get(i)).put("s", t));
        }
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.trim().isEmpty())
            prompt.append("Dialog sebelumnya (hanya untuk konteks):\n").append(limit(history.trim(), 700)).append("\n\n");
        prompt.append("Terjemahkan:\n").append(input.toString());
        // Indonesian is ~25% longer than English; JSON framing + thinking headroom on top.
        int maxTokens = Math.max(500, Math.min(2200, (int) (chars * 1.2) + 60 * ids.size() + 250));

        JSONObject request = new JSONObject();
        request.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", BATCH_SYSTEM))));
        request.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt.toString())))));
        request.put("generationConfig", new JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens)
                .put("responseMimeType", "application/json"));
        String raw;
        try { raw = post(apiKey, request, token); }
        catch (Exception e) {
            if (token != null && token.isCancelled()) throw new CancelledException();
            throw e;
        }
        if (token != null && token.isCancelled()) throw new CancelledException();
        return parseBatch(raw);
    }

    /** Tolerant parser: accepts a bare array, an object wrapping an array, and ```json fences. */
    static Map<Integer, String> parseBatch(String raw) throws Exception {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        JSONArray arr;
        if (t.startsWith("[")) arr = new JSONArray(t);
        else {
            JSONObject o = new JSONObject(t);
            arr = o.optJSONArray("translations");
            if (arr == null) arr = o.optJSONArray("items");
            if (arr == null) arr = o.optJSONArray("result");
            if (arr == null) throw new Exception("Format balasan Gemini tidak dikenali.");
        }
        Map<Integer, String> out = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.has("id")) continue;
            String v = o.optString("t", o.optString("translation", o.optString("text", null)));
            if (v == null) continue;
            out.put(o.optInt("id"), v.trim());
        }
        return out;
    }

    private static String post(String apiKey, JSONObject request, CancelToken token) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (token != null && token.isCancelled()) throw new CancelledException();
            try { return postOnce(apiKey, request, token); }
            catch (RetryableException e) { last = e; try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
        }
        throw last == null ? new Exception("Gagal menghubungi Gemini.") : last;
    }

    private static final class RetryableException extends Exception {
        RetryableException(String m) { super(m); }
    }

    private static String postOnce(String apiKey, JSONObject request, CancelToken token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent").openConnection();
        if (token != null) token.attach(conn);
        try {
            conn.setRequestMethod("POST"); conn.setConnectTimeout(9000); conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setDoOutput(true);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream os = conn.getOutputStream()) { os.write(body); }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = stream == null ? "" : readAll(stream);
            if (status < 200 || status >= 300) {
                String msg = "HTTP " + status;
                try { msg = new JSONObject(response).getJSONObject("error").optString("message", msg); }
                catch (Exception ignored) { }
                if (status == 429) throw quotaError(response, msg);
                if (status == 500 || status == 503) throw new RetryableException("Server Gemini sibuk (" + status + "). Coba lagi.");
                throw new Exception("Gemini API: " + limit(msg, 220));
            }
            JSONArray candidates = new JSONObject(response).optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new Exception("Gemini tidak mengembalikan teks; coba pilih dialog lain.");
            JSONArray parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder out = new StringBuilder();
            for (int i=0; i<parts.length(); i++) {
                String chunk = parts.getJSONObject(i).optString("text", "");
                if (!chunk.isEmpty()) { if (out.length() > 0) out.append('\n'); out.append(chunk); }
            }
            if (out.toString().trim().isEmpty()) throw new Exception("Respons Gemini kosong.");
            return out.toString().trim();
        } finally { conn.disconnect(); }
    }
    private static String readAll(InputStream in) throws Exception {
        try(InputStream stream=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n=stream.read(buffer))>=0) {
                out.write(buffer, 0, n);
                if (out.size() > 512_000) throw new Exception("Respons server terlalu besar.");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static String limit(String s, int max) { return s == null ? "" : (s.length()>max ? s.substring(0,max) : s); }
}
