package com.aia.sparkfeedback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the {@code feedback} schema on first boot and loads the mock policies used to sign in during the POC. */
@Component
@Order(1)
public class SchemaInit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInit.class);
    private final JdbcTemplate jdbc;
    private final String schema;

    public SchemaInit(JdbcTemplate jdbc, @Value("${spark.db.schema:feedback}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.policy (
                  policy_no      text PRIMARY KEY,
                  dob            date NOT NULL,
                  holder_name    text NOT NULL,
                  product_name   text NOT NULL,
                  channel        text NOT NULL,
                  preferred_lang text NOT NULL DEFAULT 'en',
                  purchased_on   date NOT NULL
                )""".formatted(schema));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.session (
                  id               uuid PRIMARY KEY,
                  token_hash       text NOT NULL,
                  policy_no        text NOT NULL UNIQUE REFERENCES %s.policy(policy_no),
                  channel          text NOT NULL,
                  questionnaire_key text,
                  questionnaire    jsonb,
                  language         text NOT NULL DEFAULT 'en',
                  status           text NOT NULL DEFAULT 'IN_PROGRESS',
                  current_question text,
                  unclear_attempts int NOT NULL DEFAULT 0,
                  created_at       timestamptz NOT NULL DEFAULT now(),
                  updated_at       timestamptz NOT NULL DEFAULT now(),
                  completed_at     timestamptz
                )""".formatted(schema, schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS session_token_idx ON %s.session(token_hash)".formatted(schema));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.answer (
                  id            bigserial PRIMARY KEY,
                  session_id    uuid NOT NULL REFERENCES %s.session(id) ON DELETE CASCADE,
                  question_key  text NOT NULL,
                  question_text text NOT NULL,
                  answer_text   text NOT NULL,
                  decision      text NOT NULL,
                  confidence    numeric(4,3),
                  source        text NOT NULL,
                  language      text NOT NULL,
                  answered_at   timestamptz NOT NULL DEFAULT now(),
                  UNIQUE (session_id, question_key)
                )""".formatted(schema, schema));

        // A permanent snapshot of each finished (or abandoned) run. "Start again" clears the live answers, never this table.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.submission (
                  id                bigserial PRIMARY KEY,
                  session_id        uuid NOT NULL,
                  policy_no         text NOT NULL,
                  holder_name       text NOT NULL,
                  product_name      text NOT NULL,
                  channel           text NOT NULL,
                  questionnaire_key text,
                  language          text NOT NULL,
                  status            text NOT NULL,
                  started_at        timestamptz NOT NULL,
                  finished_at       timestamptz NOT NULL DEFAULT now(),
                  answers           jsonb NOT NULL DEFAULT '[]'::jsonb
                )""".formatted(schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS submission_finished_idx ON %s.submission(finished_at DESC)".formatted(schema));

        // Runs finished before snapshots existed: save them once.
        jdbc.update("INSERT INTO " + schema + ".submission(session_id, policy_no, holder_name, product_name, channel, questionnaire_key, language, status, started_at, finished_at, answers) "
                + "SELECT se.id, se.policy_no, p.holder_name, p.product_name, se.channel, se.questionnaire_key, se.language, 'COMPLETED', se.created_at, COALESCE(se.completed_at, now()), "
                + AdminStore.ANSWERS_JSON.formatted(schema) + " FROM " + schema + ".session se JOIN " + schema + ".policy p USING (policy_no) "
                + "WHERE se.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM " + schema + ".submission x WHERE x.session_id = se.id)");

        // Runs saved earlier took their start from the session row (reused by "Start again"): re-derive both times from the run's own answers.
        jdbc.update("UPDATE " + schema + ".submission x SET started_at = r.first, finished_at = r.last FROM ("
                + "SELECT s.id, min((a->>'answeredAt')::timestamptz) AS first, max((a->>'answeredAt')::timestamptz) AS last "
                + "FROM " + schema + ".submission s, jsonb_array_elements(s.answers) a GROUP BY s.id) r "
                + "WHERE x.id = r.id AND (x.started_at <> r.first OR x.finished_at <> r.last)");

        // Policy numbers are one letter + 9 digits (e.g. L123456789). Move the older mock numbers over, keeping their sessions and saved answers.
        String[][] renamed = {
                {"SG-NF-100234", "L123456789"},
                {"SG-NF-100877", "L234567891"},
                {"SG-FT-200415", "M345678912"},
                {"SG-FT-200962", "M456789123"},
                {"SG-BC-300118", "B567891234"},
                {"SG-NF-100555", "L678912345"},
                {"SG-FT-200777", "M789123456"}
        };
        for (String[] r : renamed) {
            jdbc.update("INSERT INTO " + schema + ".policy(policy_no, dob, holder_name, product_name, channel, preferred_lang, purchased_on) "
                    + "SELECT ?, dob, holder_name, product_name, channel, preferred_lang, purchased_on FROM " + schema + ".policy WHERE policy_no = ? "
                    + "ON CONFLICT (policy_no) DO NOTHING", r[1], r[0]);
            jdbc.update("UPDATE " + schema + ".session SET policy_no = ? WHERE policy_no = ?", r[1], r[0]);
            jdbc.update("UPDATE " + schema + ".submission SET policy_no = ? WHERE policy_no = ?", r[1], r[0]);
            jdbc.update("DELETE FROM " + schema + ".policy WHERE policy_no = ?", r[0]);
        }

        // Mock customers for the POC. Policy number + date of birth is the sign-in.
        Object[][] policies = {
                {"L123456789", "1988-03-14", "Tan Wei Ming", "AIA Smart Term Life", "NFTF", "en", "2026-10-28"},
                {"L234567891", "1992-11-02", "Nur Aisyah binti Rahman", "AIA Health Shield Plus", "NFTF", "ms", "2026-11-03"},
                {"M345678912", "1979-07-25", "Lim Mei Ling", "AIA Absolute Critical Cover", "FTF", "zh", "2026-10-15"},
                {"M456789123", "1985-01-30", "Rajesh Kumar", "AIA Pro Achiever", "FTF", "ta", "2026-11-06"},
                {"B567891234", "1990-09-09", "Chloe Ng", "AIA Guaranteed Protect", "BANCA", "en", "2026-11-09"},
                {"L678912345", "1995-05-21", "Priya Nair", "AIA Smart Term Life", "NFTF", "en", "2026-11-12"},
                {"M789123456", "1983-12-08", "Ahmad Faizal", "AIA Absolute Critical Cover", "FTF", "en", "2026-11-11"},
        };
        for (Object[] p : policies) {
            jdbc.update("INSERT INTO " + schema + ".policy(policy_no, dob, holder_name, product_name, channel, preferred_lang, purchased_on) "
                            + "VALUES (?, ?::date, ?, ?, ?, ?, ?::date) ON CONFLICT (policy_no) DO NOTHING",
                    p[0], p[1], p[2], p[3], p[4], p[5], p[6]);
        }
        log.info("Schema '{}' ready ({} mock policies).", schema, policies.length);
    }
}
