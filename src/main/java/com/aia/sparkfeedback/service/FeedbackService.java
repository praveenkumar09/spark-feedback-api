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
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Signs the customer in and runs one turn of their own session. Work on one session is serialised; different sessions run in parallel. */
@Service
public class FeedbackService {
    private static final Pattern POLICY_NO = Pattern.compile("[A-Z][0-9]{9}");

    public static class LoginException extends RuntimeException {
        public final int status;
        public LoginException(int status, String m) { super(m); this.status = status; }
    }

    private final SessionStore store;
    private final FeedbackGraph graph;
    private final LoginThrottle throttle;
    private final SpeechService speech;
    private final CallScriptTags tags;
    private final java.util.concurrent.ExecutorService warmup = java.util.concurrent.Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "tts-warmup"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** What Spark said last in reply to an interruption, so the voice endpoint can read it out. */
    private final ConcurrentHashMap<UUID, String> lastReply = new ConcurrentHashMap<>();

    public FeedbackService(SessionStore store, FeedbackGraph graph, LoginThrottle throttle, SpeechService speech, CallScriptTags tags) {
        this.store = store;
        this.graph = graph;
        this.throttle = throttle;
        this.speech = speech;
        this.tags = tags;
    }

    private <T> T locked(UUID id, java.util.function.Supplier<T> work) {
        ReentrantLock l = locks.computeIfAbsent(id, k -> new ReentrantLock());
        l.lock();
        try { return work.get(); } finally { l.unlock(); }
    }

    // ── sign in ──────────────────────────────────────────────────────────────────

    public LoginResult login(String policyNoRaw, String dobRaw, String language) {
        String policyNo = policyNoRaw == null ? "" : policyNoRaw.trim().toUpperCase();
        if (!POLICY_NO.matcher(policyNo).matches()) throw new LoginException(401, "bad");   // one letter + 9 digits, e.g. L123456789
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
            // Signing in begins a NEW conversation from question 1. (Reloading the page in the same tab does not sign in again: it keeps the call going.)
            Session before = store.load(id);
            if (store.answerCount(id) > 0 || !"IN_PROGRESS".equals(before.status())) store.reset(id);
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
            String decision = (String) out.get("decision");
            boolean repeat = Boolean.TRUE.equals(out.get("repeat"));
            if (repeat && "NOISE".equals(decision)) { lastReply.remove(id); return turn(id, null, null, false, true); }   // not the customer: as if nothing was heard
            String reply = repeat ? (String) out.get("reply") : null;
            if (reply != null && !reply.isBlank()) lastReply.put(id, reply); else lastReply.remove(id);
            return turn(id, (String) out.get("heard"), reply != null && !reply.isBlank() ? "INTERRUPT" : decision, repeat, false);
        });
    }

    public Turn answerAudio(UUID id, String questionKey, byte[] audio, String contentType) {
        Session s = store.load(id);
        String text = speech.transcribe(audio, contentType, s.language());
        if (text.isBlank()) return turn(id, null, null, false, true);
        return answer(id, questionKey, text, "VOICE", null);
    }

    public String transcribe(UUID id, byte[] audio, String contentType) {
        return speech.transcribe(audio, contentType, store.load(id).language());
    }

    public Turn setLanguage(UUID id, String lang) {
        return locked(id, () -> {
            store.setLanguage(id, Phrases.normalize(lang));
            return turn(id, null, null, false, false);
        });
    }

    /** The customer says their last answer was wrong: take it back and ask that question again. Not possible once the survey is finished. */
    public Turn undo(UUID id) {
        return locked(id, () -> {
            if ("IN_PROGRESS".equals(store.load(id).status())) store.undoLastAnswer(id);
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
        if ("reply".equals(what)) return lastReply.get(id);
        if ("question".equals(what)) {
            if (s.questionnaire() == null || s.currentQuestion() == null) return null;
            Question q = s.questionnaire().find(s.currentQuestion());
            return q == null ? null : spoken(q, s.language());
        }
        return ph.get(what);
    }

    /** What is shown on screen: the question, and for a CHOICE question each option on its own line ("1) ..."). */
    private static String shown(Question q, String lang) {
        if (!q.isChoice()) return q.text(lang);
        StringBuilder sb = new StringBuilder(q.text(lang));
        for (var o : q.options()) sb.append("\n").append(o.key()).append(") ").append(o.text(lang));
        return sb.toString();
    }

    /** What is read aloud: the question, then "Option 1, ...  Option 2, ...". */
    private static String spoken(Question q, String lang) {
        if (!q.isChoice()) return q.text(lang);
        StringBuilder sb = new StringBuilder(q.text(lang));
        for (var o : q.options()) sb.append(" ").append(Phrases.optionWord(lang)).append(" ").append(o.key()).append(": ").append(o.text(lang)).append(".");
        return sb.toString();
    }

    private static List<com.aia.sparkfeedback.model.Views.OptionView> optionViews(Question q, String lang) {
        if (!q.isChoice()) return List.of();
        return q.options().stream().map(o -> new com.aia.sparkfeedback.model.Views.OptionView(o.key(), o.text(lang))).toList();
    }

    /**
     * While the customer is answering this question, make the voice for the questions that could come next (each branch, and the next in order),
     * so whichever is chosen starts speaking at once instead of waiting about 1.5 s for the voice to be generated.
     */
    private void prefetchNext(Session s) {
        if (!speech.ttsEnabled()) return;
        Question cur = s.questionnaire().find(s.currentQuestion());
        if (cur == null) return;
        java.util.Set<String> mine = tags.tagsFor(s.policyNo());
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (cur.branches() != null) for (var b : cur.branches()) if (b.next() != null) keys.add(b.next());
        String byOrder = FeedbackGraph.nextApplicable(s.questionnaire(), cur.order(), mine);
        if (byOrder != null) keys.add(byOrder);
        String lang = s.language();
        for (String k : keys) {
            Question nq = s.questionnaire().find(k);
            if (nq == null) continue;
            // a branch can point at a question this customer's tags skip: the real next one is the one after it
            if (!CallScriptTags.applies(nq.key(), mine)) {
                String after = FeedbackGraph.nextApplicable(s.questionnaire(), nq.order(), mine);
                nq = after == null ? null : s.questionnaire().find(after);
                if (nq == null) continue;
            }
            final String text = spoken(nq, lang);
            warmup.submit(() -> { try { speech.speak(text, lang); } catch (Exception ignored) { /* best effort */ } });
        }
    }

    public String language(UUID id) { return store.load(id).language(); }

    private Turn turn(UUID id, String heard, String decision, boolean repeat, boolean noSpeech) {
        Session s = store.load(id);
        QuestionView qv = null;
        if ("IN_PROGRESS".equals(s.status()) && s.questionnaire() != null && s.currentQuestion() != null) {
            Question q = s.questionnaire().find(s.currentQuestion());
            if (q != null) qv = new QuestionView(q.key(), q.type(), shown(q, s.language()), store.answerCount(id) + 1, optionViews(q, s.language()));
        }
        if (qv != null) prefetchNext(s);
        boolean done = "COMPLETED".equals(s.status());
        Map<String, String> phrases = new HashMap<>(Phrases.of(s.language(), s.customerName()));
        if (repeat && "INTERRUPT".equals(decision) && lastReply.get(id) != null) phrases.put("reply", lastReply.get(id));
        if (qv != null && (CallScriptTags.LANGUAGE_QUESTION.equals(qv.key()) || (qv.options() != null && !qv.options().isEmpty()))) phrases.put("clarify", Phrases.clarifyChoice(s.language()));
        return new Turn(id.toString(), s.status(), s.language(), s.customerName(), s.channel(), qv, repeat, noSpeech, heard, decision,
                phrases, done ? store.answers(id) : List.of());
    }
}
