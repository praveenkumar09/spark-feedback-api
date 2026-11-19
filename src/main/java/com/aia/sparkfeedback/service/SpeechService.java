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
    @Value("${spark.tts.voice:nova}") private String voice;
    @Value("${spark.tts.speed:1.0}") private double speed;
    @Value("${spark.speech.key:}") private String speechKey;
    @Value("${spark.speech.region:}") private String speechRegion;
    @Value("${spark.speech.endpoint:}") private String speechEndpoint;
    @Value("${spark.speech.rate-percent:-3}") private int speechRate;
    @Value("${spark.speech.voice.en:en-SG-LunaNeural}") private String voiceEn;
    @Value("${spark.speech.voice.ms:ms-MY-YasminNeural}") private String voiceMs;
    @Value("${spark.speech.voice.zh:zh-SG-XiaoMinNeural}") private String voiceZh;
    @Value("${spark.speech.voice.ta:ta-SG-VenbaNeural}") private String voiceTa;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    private String base() { return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint; }

    private boolean azureSpeechEnabled() { return !speechKey.isBlank() && (!speechEndpoint.isBlank() || !speechRegion.isBlank()); }

    public boolean ttsEnabled() { return azureSpeechEnabled() || ttsDeployment != null && !ttsDeployment.isBlank() && !endpoint.isBlank() && !apiKey.isBlank(); }

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
                    + "Answers are usually short, such as yes, no, or one sentence. Words from English, Malay, Mandarin and Tamil may be mixed; write each word as spoken and never translate. "
                    + "Transcribe only the one person speaking closest to the microphone. Ignore background voices, television, radio, music and other people. If there is no clear speech, return nothing.");
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
        if (azureSpeechEnabled()) {
            byte[] mp3 = speakWithAzureSpeech(input, code);
            if (mp3 != null) {
                if (cache.size() >= MAX_CACHED) cache.clear();
                cache.put(key, mp3);
                return mp3;
            }                                            // fall through to the Azure OpenAI voice
        }
        if (ttsDeployment == null || ttsDeployment.isBlank() || endpoint.isBlank() || apiKey.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ttsDeployment);
            body.put("input", input);
            body.put("voice", voice);
            body.put("instructions", "en".equals(code)
                    ? "Voice: a young Singaporean woman, mid-pitch, bright and warm, clear and friendly, like a helpful customer service officer who smiles while she talks. "
                        + "Accent: natural Singapore English: crisp consonants, slightly clipped, syllable-timed rhythm, a gentle rising tone at the end of questions; subtle, not exaggerated, and no 'lah' unless it is natural. "
                        + "Pace: relaxed and conversational, with small natural pauses; never robotic, never announcer-like. Say 'AIA' as three letters, A-I-A."
                    : "Voice: a young Singaporean woman, mid-pitch, bright and warm, like a helpful customer service officer who smiles while she talks. "
                        + "Speak " + Phrases.languageName(code) + " the way a native Singaporean speaker would, naturally and conversationally, keeping 'AIA' in English. "
                        + "Pace: relaxed, with small natural pauses; never robotic. Say 'AIA' as three letters, A-I-A.");
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

    /** Azure AI Speech neural voice (Singapore English, Mandarin and Tamil; Malay from Malaysia). MP3, or null if it failed. */
    private byte[] speakWithAzureSpeech(String text, String code) {
        try {
            String voiceName = switch (code) { case "ms" -> voiceMs; case "zh" -> voiceZh; case "ta" -> voiceTa; default -> voiceEn; };
            String xmlLang = switch (code) { case "ms" -> "ms-MY"; case "zh" -> "zh-SG"; case "ta" -> "ta-SG"; default -> "en-SG"; };
            String ssml = "<speak version='1.0' xml:lang='" + xmlLang + "'><voice name='" + voiceName + "'><prosody rate='" + (speechRate >= 0 ? "+" : "") + speechRate + "%'>"
                    + xmlEscape(text) + "</prosody></voice></speak>";
            String url = !speechEndpoint.isBlank() ? speechEndpoint.replaceAll("/+$", "") + "/cognitiveservices/v1"
                    : "https://" + speechRegion.strip() + ".tts.speech.microsoft.com/cognitiveservices/v1";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Ocp-Apim-Subscription-Key", speechKey).header("Content-Type", "application/ssml+xml")
                    .header("X-Microsoft-OutputFormat", "audio-24khz-96kbitrate-mono-mp3").header("User-Agent", "spark-callback")
                    .POST(HttpRequest.BodyPublishers.ofString(ssml, StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            for (int retry = 1; retry <= 2 && (res.statusCode() == 429 || res.statusCode() >= 500); retry++) {
                Thread.sleep(500L * retry);
                res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            }
            if (res.statusCode() != 200) {
                log.warn("Azure Speech request failed ({}): using the fallback voice", res.statusCode());
                return null;
            }
            return res.body();
        } catch (Exception e) {
            log.warn("Azure Speech error: {}: using the fallback voice", e.toString());
            return null;
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
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
