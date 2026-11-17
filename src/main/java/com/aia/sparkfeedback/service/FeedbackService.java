package com.aia.sparkfeedback.service;

import com.aia.sparkfeedback.auth.LoginThrottle;
import com.aia.sparkfeedback.graph.FeedbackGraph;
import com.aia.sparkfeedback.model.Questionnaire.Question;
import com.aia.sparkfeedback.model.Views.LoginResult;
import com.aia.sparkfeedback.model.Views.QuestionView;
import com.aia.sparkfeedback.model.Views.Turn;
import com.aia.sparkfeedback.service.SessionStore.Policy;
import com.aia.sparkfeedback.service.SessionStore.Session;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Signs the customer in and runs one turn of their own session. Work on one session is serialised; different sessions run in parallel. */
@Service
public class FeedbackService {

    public static class LoginException extends RuntimeException {
        public final int status;
        public LoginException(int status, String m) { super(m); this.status = status; }
    }

    private final SessionStore store;
    private final FeedbackGraph graph;
    private final LoginThrottle throttle;
    private final SpeechService speech;
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FeedbackService(SessionStore store, FeedbackGraph graph, LoginThrottle throttle, SpeechService speech) {
        this.store = store;
        this.graph = graph;
        this.throttle = throttle;
        this.speech = speech;
    }

    private <T> T locked(UUID id, java.util.function.Supplier<T> work) {
        ReentrantLock l = locks.computeIfAbsent(id, k -> new ReentrantLock());
        l.lock();
        try { return work.get(); } finally { l.unlock(); }
    }

    // ── sign in ──────────────────────────────────────────────────────────────────

    public LoginResult login(String policyNoRaw, String dobRaw, String language) {
        String policyNo = policyNoRaw == null ? "" : policyNoRaw.trim().toUpperCase();
        if (policyNo.isEmpty() || policyNo.length() > 40) throw new LoginException(401, "bad");
        if (throttle.locked(policyNo)) throw new LoginException(429, "locked");
        LocalDate dob;
        try { dob = LocalDate.parse(dobRaw == null ? "" : dobRaw.trim()); } catch (Exception e) { throttle.failed(policyNo); throw new LoginException(401, "bad"); }
        Policy policy = store.findPolicy(policyNo, dob).orElse(null);
        if (policy == null) {
            throttle.failed(policyNo);
            throw new LoginException(401, "bad");
        }
        throttle.succeeded(policyNo);
        String token = store.newToken();
        UUID id = store.openSession(policy, token);
        return locked(id, () -> {
            if (language != null && Phrases.LANGUAGES.contains(language)) store.setLanguage(id, language);
            Session s = store.load(id);
            if (s.questionnaire() == null) graph.run(Map.of("sessionId", id.toString(), "phase", "START"));
            return new LoginResult(token, turn(id, null, null, false, false));
        });
    }

    // ── turns ────────────────────────────────────────────────────────────────────

    public Turn current(UUID id) { return turn(id, null, null, false, false); }

    public Turn answer(UUID id, String questionKey, String text, String source, String choice) {
        return locked(id, () -> {
            Session s = store.load(id);
            if (!"IN_PROGRESS".equals(s.status()) || s.currentQuestion() == null || !s.currentQuestion().equals(questionKey)) return turn(id, null, null, false, false);
            String clean = text == null ? "" : text.strip();
            if (clean.isEmpty() || clean.length() > 2000) return turn(id, null, null, false, true);
            Map<String, Object> in = new HashMap<>();
            in.put("sessionId", id.toString());
            in.put("phase", "ANSWER");
            in.put("answer", clean);
            in.put("source", source == null ? "TEXT" : source);
            in.put("choice", choice == null ? "" : choice);
            Map<String, Object> out = graph.run(in);
            return turn(id, (String) out.get("heard"), (String) out.get("decision"), Boolean.TRUE.equals(out.get("repeat")), false);
        });
    }

    public Turn answerAudio(UUID id, String questionKey, byte[] audio, String contentType) {
        Session s = store.load(id);
        String text = speech.transcribe(audio, contentType, s.language());
        if (text.isBlank()) return turn(id, null, null, false, true);
        return answer(id, questionKey, text, "VOICE", null);
    }

    public Turn setLanguage(UUID id, String lang) {
        return locked(id, () -> {
            store.setLanguage(id, Phrases.normalize(lang));
            return turn(id, null, null, false, false);
        });
    }

    /** POC convenience: wipe this session's answers and ask again from the first question (the questions are read from Strapi afresh). */
    public Turn restart(UUID id) {
        return locked(id, () -> {
            store.reset(id);
            graph.run(Map.of("sessionId", id.toString(), "phase", "START"));
            return turn(id, null, null, false, false);
        });
    }

    /** What Spark says for a fixed purpose, in the session's language. */
    public String sayText(UUID id, String what) {
        Session s = store.load(id);
        Map<String, String> ph = Phrases.of(s.language(), s.customerName());
        if ("question".equals(what)) {
            if (s.questionnaire() == null || s.currentQuestion() == null) return null;
            Question q = s.questionnaire().find(s.currentQuestion());
            return q == null ? null : q.text(s.language());
        }
        return ph.get(what);
    }

    public String language(UUID id) { return store.load(id).language(); }

    private Turn turn(UUID id, String heard, String decision, boolean repeat, boolean noSpeech) {
        Session s = store.load(id);
        QuestionView qv = null;
        if ("IN_PROGRESS".equals(s.status()) && s.questionnaire() != null && s.currentQuestion() != null) {
            Question q = s.questionnaire().find(s.currentQuestion());
            if (q != null) qv = new QuestionView(q.key(), q.type(), q.text(s.language()), store.answerCount(id) + 1);
        }
        boolean done = "COMPLETED".equals(s.status());
        return new Turn(id.toString(), s.status(), s.language(), s.customerName(), s.channel(), qv, repeat, noSpeech, heard, decision,
                Phrases.of(s.language(), s.customerName()), done ? store.answers(id) : List.of());
    }
}
