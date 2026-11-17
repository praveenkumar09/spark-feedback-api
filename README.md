# Spark Feedback API

Post-purchase feedback bot. Spring Boot + Spring AI + LangGraph4j; questions come from Strapi (its own project, `../spark-feedback-strapi`); answers are stored in Postgres.

    cp .env.example .env   # fill Azure keys
    docker compose up -d --build     # API :8090, Postgres :5434; creates the shared network `spark_feedback_net`
    (cd ../spark-feedback-strapi && cp .env.example .env && docker compose up -d --build)   # Strapi :1337 (localhost only), start it after this stack
    python3 scripts/parallel_test.py # 6 customers at once, checks nothing mixes

- Question editors: Strapi at http://localhost:1337/admin, run from `../spark-feedback-strapi` (see its README). Edit Questionnaire / Question: wording in en/ms/zh/ta, order, and Branches (`YES`/`NO`/`ANY` -> next question key; empty = end). First matching branch wins; no match = next question by order.
- Each session stores a copy of its questionnaire at start, so edits never change a session in progress. New sessions pick up edits (and "Start again" re-reads them).
- Mock customers (policy number / date of birth): see `feedback.policy`.
- Answers: `select * from feedback.answer order by session_id, id;`
- Keeping Strapi patched: pin by `package-lock.json`, rebuild monthly (`docker compose build --no-cache`), run `npm audit`, enable Renovate/Dependabot — all in the `spark-feedback-strapi` repo.
