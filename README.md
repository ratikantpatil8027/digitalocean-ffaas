# ffaas — Feature Flag as a Service

[![CI](https://github.com/ratikantpatil8027/digitalocean-ffaas/actions/workflows/ci.yml/badge.svg)](https://github.com/ratikantpatil8027/digitalocean-ffaas/actions/workflows/ci.yml)

REST API that stores feature flags and evaluates them against per-request user context (rules, optional percentage rollout, two-layer in-memory cache). Stack: Java 21, Spring Boot 3, Maven, PostgreSQL, Caffeine.

Contract: [PRD.md](./PRD.md) · Design: [ARCHITECTURE.md](./ARCHITECTURE.md)

## Quickstart

**Full stack (recommended):**

```bash
docker compose up --build
```

App listens on `http://localhost:8080`. Health: `GET /actuator/health`.

**Local app + compose Postgres:**

```bash
docker compose up postgres -d
mvn spring-boot:run
```

Defaults match `application.yml` (`DB_URL` / `DB_USER` / `DB_PASSWORD` → local Postgres on `5432`).

## Curl walkthrough

Create the sample flag from PRD §3.1:

```bash
curl -sS -X POST http://localhost:8080/api/v1/flags \
  -H 'Content-Type: application/json' \
  -d '{
    "key": "premium-dashboard",
    "name": "Premium Dashboard",
    "description": "New analytics dashboard for premium users",
    "enabled": true,
    "defaultState": false,
    "rules": [
      {
        "priority": 0,
        "serve": true,
        "conditions": [
          { "attribute": "subscriptionTier", "operator": "EQ", "value": "premium" },
          { "attribute": "region", "operator": "IN", "value": ["us-east", "eu-west"] }
        ]
      }
    ]
  }'
```

Evaluate — premium user in `us-east` → `RULE_MATCH` / enabled; free tier → `DEFAULT` / disabled:

```bash
curl -sS -X POST http://localhost:8080/api/v1/flags/premium-dashboard/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","attributes":{"subscriptionTier":"premium","region":"us-east"}}'

curl -sS -X POST http://localhost:8080/api/v1/flags/premium-dashboard/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-456","attributes":{"subscriptionTier":"free","region":"us-east"}}'
```

Rollout demo — replace rules with a 30% rollout on the matching premium rule, then evaluate several `userId`s; expect a mix of `RULE_MATCH` and `ROLLOUT_EXCLUDED` (deterministic per user):

```bash
curl -sS -X PUT http://localhost:8080/api/v1/flags/premium-dashboard \
  -H 'Content-Type: application/json' \
  -d '{
    "key": "premium-dashboard",
    "name": "Premium Dashboard",
    "description": "New analytics dashboard for premium users",
    "enabled": true,
    "defaultState": false,
    "rules": [
      {
        "priority": 0,
        "serve": true,
        "rolloutPercentage": 30,
        "conditions": [
          { "attribute": "subscriptionTier", "operator": "EQ", "value": "premium" },
          { "attribute": "region", "operator": "IN", "value": ["us-east", "eu-west"] }
        ]
      }
    ]
  }'

for id in user-a user-b user-c user-d user-e; do
  curl -sS -X POST http://localhost:8080/api/v1/flags/premium-dashboard/evaluate \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$id\",\"attributes\":{\"subscriptionTier\":\"premium\",\"region\":\"us-east\"}}"
  echo
done
```

Cache invalidation — after the PUT above, subsequent evaluates use the new rules (both cache layers are evicted on write). Toggle the global kill switch and re-evaluate to confirm `FLAG_DISABLED`:

```bash
curl -sS -X PUT http://localhost:8080/api/v1/flags/premium-dashboard \
  -H 'Content-Type: application/json' \
  -d '{
    "key": "premium-dashboard",
    "name": "Premium Dashboard",
    "enabled": false,
    "defaultState": false,
    "rules": []
  }'

curl -sS -X POST http://localhost:8080/api/v1/flags/premium-dashboard/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","attributes":{"subscriptionTier":"premium","region":"us-east"}}'
```

Delete:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE http://localhost:8080/api/v1/flags/premium-dashboard
```

## Deploying to DigitalOcean

1. Create a **Managed PostgreSQL** database and note host, port, db name, user, and password.
2. Create an **App Platform** app from this repo; choose **Dockerfile** build (no Java buildpack required).
3. Set env vars to match `application.yml`:
   - `DB_URL` — e.g. `jdbc:postgresql://HOST:PORT/DATABASE?sslmode=require`
   - `DB_USER` / `DB_PASSWORD` — from the managed DB
4. Health check path: `/actuator/health` (port `8080`).
5. Flyway runs on startup (`ddl-auto: validate`); ensure the DB is reachable before the first deploy.

## Known limitations

See [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-known-limitations--future-work) for the full list. Highlights:

- **Single-instance cache** — write-through eviction is process-local; multi-instance needs pub/sub invalidation or TTL-only staleness.
- **No auth / rate limiting** — front with a gateway in production.
- **H2 for repository unit tests** — JSONB behavior can differ from Postgres.
