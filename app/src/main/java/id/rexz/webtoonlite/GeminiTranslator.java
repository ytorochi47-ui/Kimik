package id.rexz.webtoonlite;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
        return post(apiKey, request);
    }

    private static String post(String apiKey, JSONObject request) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return postOnce(apiKey, request); }
            catch (RetryableException e) { last = e; try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
        }
        throw last == null ? new Exception("Gagal menghubungi Gemini.") : last;
    }

    private static final class RetryableException extends Exception {
        RetryableException(String m) { super(m); }
    }

    private static String postOnce(String apiKey, JSONObject request) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent").openConnection();
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
                if (status == 429) throw new Exception("Kuota gratis/rate limit habis (429). Coba lagi nanti.");
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
