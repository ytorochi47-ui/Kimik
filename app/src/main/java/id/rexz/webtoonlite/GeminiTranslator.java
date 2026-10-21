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

    public static String translate(String apiKey, String selected, String nearby, String history) throws Exception {
        if (selected.trim().isEmpty()) throw new IllegalArgumentException("Dialog belum terbaca.");
        String instruction = "Kamu editor penerjemahan webtoon bahasa Inggris ke Indonesia. " +
                "Terjemahkan DIALOG TARGET secara utuh ke bahasa Indonesia yang mengalir, natural, sesuai emosi dan karakter, " +
                "dengan gaya percakapan komik. Jangan kaku, jangan terlalu gaul bila sumbernya serius. " +
                "Jaga nama, istilah, humor, nada, dan ambiguitas. Jangan mengarang gender, relasi, " +
                "alur, atau informasi yang tidak terbukti. Konteks di layar hanya petunjuk, BUKAN teks yang harus diterjemahkan. " +
                "Riwayat yang pernah dipilih pengguna belum tentu berurutan. Jika OCR keliru dan " +
                "perbaikannya jelas, koreksi secara konservatif. Jawab HANYA terjemahan target dalam bahasa Indonesia.";
        String prompt = "DIALOG TARGET:\n" + limit(selected, 2200) +
                "\n\nKONTEKS TEKS LAIN YANG TERLIHAT DI LAYAR (mungkin tidak berurutan):\n" + limit(nearby, 1200) +
                "\n\nRIWAYAT PILIHAN TERDAHULU (tidak selalu kronologis):\n" + limit(history, 1700);

        JSONObject request = new JSONObject();
        request.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instruction))));
        request.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
        request.put("generationConfig", new JSONObject().put("temperature", 0.25).put("maxOutputTokens", 800));
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
