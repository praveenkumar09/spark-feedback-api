package com.aia.sparkfeedback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** Read-only view of everything customers have answered, for the admin panel. */
@Repository
public class AdminStore {

    /** A session's answers as a JSON array, oldest first. The %s is the schema; {@code se} is the session row it is correlated with. */
    static final String ANSWERS_JSON = "COALESCE((SELECT jsonb_agg(jsonb_build_object('key', a.question_key, 'question', a.question_text, 'answer', a.answer_text, "
            + "'decision', a.decision, 'confidence', a.confidence, 'source', a.source, 'language', a.language, 'answeredAt', a.answered_at) ORDER BY a.id) "
            + "FROM %s.answer a WHERE a.session_id = se.id), '[]'::jsonb)";

    /** A run starts with its first answer and ends with its last one, not when the (reused) session row was created. {@code se} is the session row. */
    static final String RUN_START = "COALESCE((SELECT min(a.answered_at) FROM %s.answer a WHERE a.session_id = se.id), se.updated_at)";
    static final String RUN_END = "COALESCE((SELECT max(a.answered_at) FROM %s.answer a WHERE a.session_id = se.id), se.updated_at)";

    public record Response(String id, String kind, String status, String sessionId, String policyNo, String holderName, String productName, String channel,
                           String questionnaireKey, String language, OffsetDateTime startedAt, OffsetDateTime finishedAt, JsonNode answers) {}

    /** One row of the Data tab: the policy number and the call script it is called with. */
    public record Customer(String policyNo, String callScript) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String s;

    public AdminStore(JdbcTemplate jdbc, @Value("${spark.db.schema:feedback}") String schema) {
        this.jdbc = jdbc;
        this.s = schema;
    }

    /** Saved runs (finished or restarted) plus runs still in progress that already have an answer, newest first. */
    public List<Response> responses(int limit) {
        String sql = "SELECT * FROM ("
                + " SELECT 'sub-' || id AS id, 'saved' AS kind, status, session_id::text, policy_no, holder_name, product_name, channel, questionnaire_key, language, started_at, finished_at, answers::text"
                + " FROM " + s + ".submission"
                + " UNION ALL"
                + " SELECT 'live-' || se.id, 'live', 'IN_PROGRESS', se.id::text, se.policy_no, p.holder_name, p.product_name, se.channel, se.questionnaire_key, se.language, " + RUN_START.formatted(s) + ", " + RUN_END.formatted(s) + ", "
                + ANSWERS_JSON.formatted(s) + "::text"
                + " FROM " + s + ".session se JOIN " + s + ".policy p USING (policy_no)"
                + " WHERE se.status = 'IN_PROGRESS' AND EXISTS (SELECT 1 FROM " + s + ".answer a WHERE a.session_id = se.id)"
                + ") r ORDER BY finished_at DESC LIMIT ?";
        return jdbc.query(sql, (rs, i) -> {
            JsonNode answers;
            try { answers = mapper.readTree(rs.getString(13)); } catch (Exception e) { throw new IllegalStateException(e); }
            return new Response(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class), answers);
        }, limit);
    }

    /** Every customer on the call list: policy number (proposal_num) and call script, in list order. */
    public List<Customer> customers() {
        return jdbc.query("SELECT proposal_num, call_script FROM " + s + ".callscript_customer ORDER BY s_no",
                (rs, i) -> new Customer(rs.getString(1), rs.getString(2)));
    }
}
