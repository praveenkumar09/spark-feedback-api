package com.aia.sparkfeedback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spark's ears and voice: Azure OpenAI gpt-4o-transcribe (speech to text) and gpt-4o-mini-tts (text to speech). The key never leaves the server. */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);
    private static final int MAX_CACHED = 300;

    @Value("${spark.openai.endpoint:}") private String endpoint;
    @Value("${spark.openai.api-key:}") private String apiKey;
    @Value("${spark.openai.api-version:2025-03-01-preview}") private String apiVersion;
    @Value("${spark.openai.transcribe-deployment:gpt-4o-transcribe}") private String sttDeployment;
    @Value("${spark.openai.tts-deployment:}") private String ttsDeployment;
    @Value("${spark.tts.voice:coral}") private String voice;
    @Value("${spark.tts.speed:1.1}") private double speed;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    private String base() { return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint; }

    public boolean ttsEnabled() { return ttsDeployment != null && !ttsDeployment.isBlank() && !endpoint.isBlank() && !apiKey.isBlank(); }

    // ── speech to text ───────────────────────────────────────────────────────────

    /** The words in the recording, or "" if nothing was heard. */
    public String transcribe(byte[] audio, String contentType, String lang) {
        try {
            String ext = contentType != null && contentType.contains("mp4") ? "mp4" : contentType != null && contentType.contains("ogg") ? "ogg"
                    : contentType != null && contentType.contains("wav") ? "wav" : "webm";
            String boundary = "----spark" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            file(body, boundary, "file", "answer." + ext, contentType == null ? "audio/webm" : contentType, audio);
            field(body, boundary, "response_format", "json");
            field(body, boundary, "language", Phrases.normalize(lang));
            field(body, boundary, "prompt", "A customer answering a short feedback question about the insurance policy they just bought in Singapore. "
                    + "Answers are usually short, such as yes, no, or one sentence. Words from English, Malay, Mandarin and Tamil may be mixed; write each word as spoken and never translate.");
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/openai/deployments/" + sttDeployment + "/audio/transcriptions?api-version=" + apiVersion))
                    .timeout(Duration.ofSeconds(40)).header("api-key", apiKey).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (int retry = 1; retry <= 2 && (res.statusCode() == 429 || res.statusCode() >= 500); retry++) {
                Thread.sleep(600L * retry);
                res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (res.statusCode() / 100 != 2) {
                log.warn("Transcription failed ({}): {}", res.statusCode(), res.body().replaceAll("\\s+", " ").substring(0, Math.min(300, res.body().length())));
                return "";
            }
            JsonNode root = mapper.readTree(res.body());
            return root.path("text").asText("").strip();
        } catch (Exception e) {
            log.warn("Transcription error: {}", e.toString());
            return "";
        }
    }

    // ── text to speech ───────────────────────────────────────────────────────────

    /** MP3 audio, or null if speech is not available (the browser then speaks with its own voice). */
    public byte[] speak(String text, String lang) {
        if (!ttsEnabled() || text == null || text.isBlank()) return null;
        String input = text.strip();
        if (input.length() > 700) input = input.substring(0, 700);
        String code = Phrases.normalize(lang);
        String key = code + "|" + input;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ttsDeployment);
            body.put("input", input);
            body.put("voice", voice);
            body.put("instructions", "You are Spark, a warm, friendly assistant asking a customer a few feedback questions after they bought a policy. "
                    + "Sound like a kind, attentive person, never a call-centre script. Relaxed natural pace, brief pauses at commas and full stops. "
                    + "Say 'AIA' as three letters, A-I-A." + ("en".equals(code) ? "" : " Speak " + Phrases.languageName(code) + " naturally, like a native speaker, keeping 'AIA' in English."));
            body.put("speed", Math.max(0.5, Math.min(2.0, speed)));
            body.put("response_format", "mp3");
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/openai/deployments/" + ttsDeployment + "/audio/speech?api-version=" + apiVersion))
                    .timeout(Duration.ofSeconds(25)).header("api-key", apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            for (int retry = 1; retry <= 2 && (res.statusCode() == 429 || res.statusCode() >= 500); retry++) {
                Thread.sleep(500L * retry);
                res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            }
            if (res.statusCode() != 200) {
                log.warn("Speech request failed ({})", res.statusCode());
                return null;
            }
            if (cache.size() >= MAX_CACHED) cache.clear();
            cache.put(key, res.body());
            return res.body();
        } catch (Exception e) {
            log.warn("Speech error: {}", e.toString());
            return null;
        }
    }

    private static void field(ByteArrayOutputStream o, String b, String name, String value) throws java.io.IOException {
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void file(ByteArrayOutputStream o, String b, String name, String filename, String type, byte[] data) throws java.io.IOException {
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(data);
        o.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
