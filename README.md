# Spark Feedback API

Post-purchase feedback bot. Spring Boot + Spring AI + LangGraph4j; questions come from Strapi; answers are stored in Postgres.

    cp .env.example .env   # fill Azure keys
    docker compose up -d --build     # API :8090, Postgres :5434, Strapi :1337 (localhost only)
    python3 scripts/parallel_test.py # 6 customers at once, checks nothing mixes

- Question editors: http://localhost:1337/admin (login from STRAPI_ADMIN_EMAIL / STRAPI_ADMIN_PASSWORD). Edit Questionnaire / Question: wording in en/ms/zh/ta, order, and Branches (`YES`/`NO`/`ANY` -> next question key; empty = end). First matching branch wins; no match = next question by order.
- Each session stores a copy of its questionnaire at start, so edits never change a session in progress. New sessions pick up edits (and "Start again" re-reads them).
- Mock customers (policy number / date of birth): see `feedback.policy`.
- Answers: `select * from feedback.answer order by session_id, id;`
- Keeping Strapi patched: pin by `package-lock.json`, rebuild monthly (`docker compose build --no-cache strapi`), run `npm audit` in `strapi/`, enable Renovate/Dependabot on this repo.
