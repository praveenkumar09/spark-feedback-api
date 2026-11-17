package com.aia.sparkfeedback.service;

import com.aia.sparkfeedback.model.Questionnaire;
import com.aia.sparkfeedback.model.Views.AnswerView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All reads and writes of policies, sessions and answers. Every query is keyed by the session id, so sessions can never mix. */
@Repository
public class SessionStore {

    public record Policy(String policyNo, String holderName, String productName, String channel, String preferredLang) {}

    public record Session(UUID id, String policyNo, String customerName, String channel, String questionnaireKey, Questionnaire questionnaire,
                          String language, String status, String currentQuestion, int unclearAttempts) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String s;
    private final int ttlHours;
    private final SecureRandom random = new SecureRandom();

    public SessionStore(JdbcTemplate jdbc, @Value("${spark.db.schema:feedback}") String schema, @Value("${spark.session.ttl-hours:12}") int ttlHours) {
        this.jdbc = jdbc;
        this.s = schema;
        this.ttlHours = ttlHours;
    }

    // ── policies ─────────────────────────────────────────────────────────────────

    public Optional<Policy> findPolicy(String policyNo, LocalDate dob) {
        return jdbc.query("SELECT policy_no, holder_name, product_name, channel, preferred_lang FROM " + s + ".policy WHERE policy_no = ? AND dob = ?",
                (rs, i) -> new Policy(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), policyNo, dob).stream().findFirst();
    }

    // ── sessions ─────────────────────────────────────────────────────────────────

    public String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Opens the policy's feedback session (one per policy) and gives it a fresh secret token. A policy already in progress is resumed.
     * Two sign-ins at the same moment still end up with one session (unique policy number).
     */
    public UUID openSession(Policy p, String token) {
        jdbc.update("INSERT INTO " + s + ".session(id, token_hash, policy_no, channel, language) VALUES (?, ?, ?, ?, ?) ON CONFLICT (policy_no) DO NOTHING",
                UUID.randomUUID(), hash(token), p.policyNo(), p.channel(), p.preferredLang());
        jdbc.update("UPDATE " + s + ".session SET token_hash = ?, updated_at = now() WHERE policy_no = ?", hash(token), p.policyNo());
        return jdbc.queryForObject("SELECT id FROM " + s + ".session WHERE policy_no = ?", UUID.class, p.policyNo());
    }

    public Optional<UUID> sessionForToken(String token) {
        return jdbc.query("SELECT id FROM " + s + ".session WHERE token_hash = ? AND updated_at > now() - make_interval(hours => ?)",
                (rs, i) -> rs.getObject(1, UUID.class), hash(token), ttlHours).stream().findFirst();
    }

    public Session load(UUID id) {
        return jdbc.query("SELECT s.id, s.policy_no, p.holder_name, s.channel, s.questionnaire_key, s.questionnaire::text, s.language, s.status, "
                        + "s.current_question, s.unclear_attempts FROM " + s + ".session s JOIN " + s + ".policy p USING (policy_no) WHERE s.id = ?",
                (rs, i) -> {
                    Questionnaire q = null;
                    String json = rs.getString(6);
                    if (json != null) {
                        try { q = mapper.readValue(json, Questionnaire.class); } catch (Exception e) { throw new IllegalStateException(e); }
                    }
                    return new Session(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), q,
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10));
                }, id).stream().findFirst().orElseThrow(() -> new IllegalStateException("No such session"));
    }

    public void saveQuestionnaire(UUID id, Questionnaire q, String firstQuestionKey) {
        try {
            jdbc.update("UPDATE " + s + ".session SET questionnaire_key = ?, questionnaire = ?::jsonb, current_question = ?, unclear_attempts = 0, "
                    + "status = 'IN_PROGRESS', updated_at = now() WHERE id = ?", q.key(), mapper.writeValueAsString(q), firstQuestionKey, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setLanguage(UUID id, String lang) {
        jdbc.update("UPDATE " + s + ".session SET language = ?, updated_at = now() WHERE id = ?", lang, id);
    }

    public void setUnclear(UUID id, int attempts) {
        jdbc.update("UPDATE " + s + ".session SET unclear_attempts = ?, updated_at = now() WHERE id = ?", attempts, id);
    }

    /** Moves to the next question (key), or completes the session when the key is null. */
    public void advance(UUID id, String nextKey) {
        if (nextKey == null) {
            jdbc.update("UPDATE " + s + ".session SET status = 'COMPLETED', current_question = NULL, unclear_attempts = 0, completed_at = now(), updated_at = now() WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE " + s + ".session SET current_question = ?, unclear_attempts = 0, updated_at = now() WHERE id = ?", nextKey, id);
        }
    }

    /** POC convenience: forget this session's answers and begin again. */
    public void reset(UUID id) {
        jdbc.update("DELETE FROM " + s + ".answer WHERE session_id = ?", id);
        jdbc.update("UPDATE " + s + ".session SET status = 'IN_PROGRESS', questionnaire = NULL, questionnaire_key = NULL, current_question = NULL, "
                + "unclear_attempts = 0, completed_at = NULL, updated_at = now() WHERE id = ?", id);
    }

    // ── answers ──────────────────────────────────────────────────────────────────

    public void saveAnswer(UUID id, String key, String questionText, String answer, String decision, Double confidence, String source, String lang) {
        jdbc.update("INSERT INTO " + s + ".answer(session_id, question_key, question_text, answer_text, decision, confidence, source, language) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id, question_key) DO UPDATE SET question_text = EXCLUDED.question_text, "
                        + "answer_text = EXCLUDED.answer_text, decision = EXCLUDED.decision, confidence = EXCLUDED.confidence, source = EXCLUDED.source, "
                        + "language = EXCLUDED.language, answered_at = now()",
                id, key, questionText, answer, decision, confidence, source, lang);
    }

    public List<AnswerView> answers(UUID id) {
        return jdbc.query("SELECT question_key, question_text, answer_text, decision, source FROM " + s + ".answer WHERE session_id = ? ORDER BY id",
                (rs, i) -> new AnswerView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
    }

    public int answerCount(UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + s + ".answer WHERE session_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
