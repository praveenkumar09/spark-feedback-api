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

        // Mock customers for the POC. Policy number + date of birth is the sign-in.
        Object[][] policies = {
                {"SG-NF-100234", "1988-03-14", "Tan Wei Ming", "AIA Smart Term Life", "NFTF", "en", "2026-10-28"},
                {"SG-NF-100877", "1992-11-02", "Nur Aisyah binti Rahman", "AIA Health Shield Plus", "NFTF", "ms", "2026-11-03"},
                {"SG-FT-200415", "1979-07-25", "Lim Mei Ling", "AIA Absolute Critical Cover", "FTF", "zh", "2026-10-15"},
                {"SG-FT-200962", "1985-01-30", "Rajesh Kumar", "AIA Pro Achiever", "FTF", "ta", "2026-11-06"},
                {"SG-BC-300118", "1990-09-09", "Chloe Ng", "AIA Guaranteed Protect", "BANCA", "en", "2026-11-09"},
                {"SG-NF-100555", "1995-05-21", "Priya Nair", "AIA Smart Term Life", "NFTF", "en", "2026-11-12"},
                {"SG-FT-200777", "1983-12-08", "Ahmad Faizal", "AIA Absolute Critical Cover", "FTF", "en", "2026-11-11"},
        };
        for (Object[] p : policies) {
            jdbc.update("INSERT INTO " + schema + ".policy(policy_no, dob, holder_name, product_name, channel, preferred_lang, purchased_on) "
                            + "VALUES (?, ?::date, ?, ?, ?, ?, ?::date) ON CONFLICT (policy_no) DO NOTHING",
                    p[0], p[1], p[2], p[3], p[4], p[5], p[6]);
        }
        log.info("Schema '{}' ready ({} mock policies).", schema, policies.length);
    }
}
