# ffaas — Feature Flag as a Service

[![CI](https://github.com/ratikantpatil8027/digitalocean-ffaas/actions/workflows/ci.yml/badge.svg)](https://github.com/ratikantpatil8027/digitalocean-ffaas/actions/workflows/ci.yml)

REST API that stores feature flags and evaluates them against per-request user context (rules, optional percentage rollout, two-layer in-memory cache). Stack: Java 21, Spring Boot 3, Maven, PostgreSQL, Caffeine.

Contract: [PRD.md](./PRD.md) · Design: [ARCHITECTURE.md](./ARCHITECTURE.md)

Interactive OpenAPI UI (when running locally or after deploy): [/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## Live demo

**Demo environment — unauthenticated, may be torn down.**

| | |
|---|---|
| Base URL | https://plankton-app-mjcf9.ondigitalocean.app |
| Health | [GET /actuator/health](https://plankton-app-mjcf9.ondigitalocean.app/actuator/health) |

```bash
# List flags
curl -sS 'https://plankton-app-mjcf9.ondigitalocean.app/api/v1/flags?page=0&size=20'
# → {"items":[{"key":"premium-dashboard",...}],"page":0,"size":20,"totalItems":1}

# Evaluate — premium / us-east → RULE_MATCH
curl -sS -X POST 'https://plankton-app-mjcf9.ondigitalocean.app/api/v1/flags/premium-dashboard/evaluate' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","attributes":{"subscriptionTier":"premium","region":"us-east"}}'
# → {"flagKey":"premium-dashboard","enabled":true,"reason":"RULE_MATCH","matchedRuleId":"9268266b-95f0-42da-afcc-3c8ae91bbe18"}

# Evaluate — free tier → DEFAULT (disabled)
curl -sS -X POST 'https://plankton-app-mjcf9.ondigitalocean.app/api/v1/flags/premium-dashboard/evaluate' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-456","attributes":{"subscriptionTier":"free","region":"us-east"}}'
# → {"flagKey":"premium-dashboard","enabled":false,"reason":"DEFAULT","matchedRuleId":null}
```

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

Local stack (`http://localhost:8080`). Create the sample flag from PRD §3.1:

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

## API reference

Full contract: [PRD.md](./PRD.md). Summary:

| Method | Path | Purpose | Success | Errors |
|--------|------|---------|---------|--------|
| `POST` | `/api/v1/flags` | Create flag | `201` + `Location` | `400` `VALIDATION_FAILED` / `MALFORMED_JSON`, `409` `DUPLICATE_KEY` |
| `GET` | `/api/v1/flags/{key}` | Get flag | `200` | `404` `FLAG_NOT_FOUND` |
| `GET` | `/api/v1/flags?page&size` | List flags (paged) | `200` | `400` validation |
| `PUT` | `/api/v1/flags/{key}` | Replace mutable fields + rules | `200` | `400`, `404` `FLAG_NOT_FOUND` |
| `DELETE` | `/api/v1/flags/{key}` | Delete flag | `204` | `404` `FLAG_NOT_FOUND` |
| `POST` | `/api/v1/flags/{key}/evaluate` | Evaluate against user context | `200` | `400`, `404` `FLAG_NOT_FOUND`, `503` `SERVICE_UNAVAILABLE` |
| `GET` | `/actuator/health` | Liveness/readiness | `200` | — |

All errors use the standard envelope (`status`, `error`, `message`, `details`, `timestamp`, `path`). Unmapped paths return `404` `NOT_FOUND`.

### Create flag — example

Request (`POST /api/v1/flags`):

```json
{
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
}
```

Response `201` (shape verified against the live demo):

```json
{
  "key": "premium-dashboard",
  "name": "Premium Dashboard",
  "description": "New analytics dashboard for premium users",
  "enabled": true,
  "defaultState": false,
  "rules": [
    {
      "id": "9268266b-95f0-42da-afcc-3c8ae91bbe18",
      "priority": 0,
      "serve": true,
      "rolloutPercentage": null,
      "conditions": [
        { "attribute": "subscriptionTier", "operator": "EQ", "value": "premium" },
        { "attribute": "region", "operator": "IN", "value": ["us-east", "eu-west"] }
      ]
    }
  ],
  "createdAt": "2026-07-18T06:44:22.049332Z",
  "updatedAt": "2026-07-18T06:44:22.049332Z"
}
```

### Evaluate — example

Request (`POST /api/v1/flags/premium-dashboard/evaluate`):

```json
{
  "userId": "user-123",
  "attributes": {
    "subscriptionTier": "premium",
    "region": "us-east"
  }
}
```

Response `200` (live):

```json
{
  "flagKey": "premium-dashboard",
  "enabled": true,
  "reason": "RULE_MATCH",
  "matchedRuleId": "9268266b-95f0-42da-afcc-3c8ae91bbe18"
}
```

## How it works

Depth: [ARCHITECTURE.md](./ARCHITECTURE.md).

**Evaluate lifecycle.** The request is validated (`userId`, scalar attributes) → L2 evaluation-result cache is checked → on miss, L1 flag-definition cache is checked → on L1 miss the flag aggregate is loaded from Postgres (or served stale from L1 if the DB is down) → `RuleEvaluator` decides ON/OFF → the response is stored in L2 (and L1 if newly loaded) and returned.

**Storage model.** A flag is an aggregate: one `feature_flags` row plus ordered `flag_rules` rows. Conditions live as a JSON array on the rule row (Flyway uses `JSON` for H2/Postgres dual-run; treat as document/JSONB on Postgres). JSON on the rule keeps the schema compact and avoids a third conditions table while still allowing rule-level priority uniqueness.

```text
feature_flags (id, key, name, description, enabled, default_state, created_at, updated_at)
flag_rules    (id, flag_id → feature_flags, priority, serve, rollout_percentage, conditions JSON)
              UNIQUE (flag_id, priority)
```

**Cache schema** (from `FlagCache` / `EvaluationResultCache`):

| Layer | Key | Value | TTL | Invalidation |
|-------|-----|-------|-----|--------------|
| L1 | `"premium-dashboard"` (flag key) | Detached `FeatureFlag` snapshot (rules + conditions) | `60s` (`ffaas.cache.flag-ttl`) | Evicted on update/delete of that key |
| L2 | `"premium-dashboard:<sha256hex>"` | `EvaluateResponse` | `30s` (`ffaas.cache.evaluation-ttl`) | All keys for the flag evicted on update/delete |

L2 context normalization (`EvaluationResultCache.contextHash`):

1. Start with `userId=<id>`.
2. Sort attribute keys (`TreeMap`), append each as `\|key=<typedValue>`.
3. Typed values: booleans `b:…`, numbers `n:…`, strings `s:…`, other `o:…`.
4. SHA-256 hex of that string.

Example for `userId=user-123`, attributes `{subscriptionTier: "premium", region: "us-east"}`:

```text
normalized = userId=user-123|region=s:us-east|subscriptionTier=s:premium
L2 key     = premium-dashboard:dd24c8fdf3076b9044672d169828f1218dc1da911f1def3eff8eb627448184cb
```

**Rule semantics**

- Rules evaluated in ascending `priority` (lower first).
- Conditions within a rule are AND-ed; missing attributes fail the condition (never throw).
- First matching rule wins (no fall-through).
- No match → flag `defaultState` with reason `DEFAULT`; global `enabled=false` → `FLAG_DISABLED`.
- Optional `rolloutPercentage`: bucket = `abs(murmur3_32(flagKey + ":" + userId)) % 100`; include iff `bucket < percentage`; exclude → `ROLLOUT_EXCLUDED` + `defaultState` (no fall-through).

## Code map

| Class | Package | Responsibility |
|-------|---------|----------------|
| `FlagController` | `com.ffaas.api` | Flag CRUD HTTP endpoints |
| `EvaluationController` | `com.ffaas.api` | Evaluate HTTP endpoint |
| `GlobalExceptionHandler` | `com.ffaas.api` | Maps exceptions to the standard error envelope |
| `CreateFlagRequest` | `com.ffaas.api.dto` | Create-flag request body + validation |
| `EvaluateRequest` / `EvaluateResponse` | `com.ffaas.api.dto` | Evaluate request/response DTOs |
| `ErrorResponse` | `com.ffaas.api.dto` | Shared API error envelope |
| `FlagService` | `com.ffaas.service` | CRUD persistence, write-through cache eviction |
| `EvaluationService` | `com.ffaas.service` | L2→L1→DB evaluate orchestration + DB-down fallback |
| `RuleEvaluator` | `com.ffaas.engine` | Pure rule matching + rollout decisions |
| `RolloutBucketer` | `com.ffaas.engine` | Deterministic murmur3 bucket `0..99` |
| `FlagCache` | `com.ffaas.cache` | L1 Caffeine flag-definition cache + per-key epochs |
| `EvaluationResultCache` | `com.ffaas.cache` | L2 Caffeine result cache + typed context hashing |
| `FeatureFlag` / `Rule` / `Condition` | `com.ffaas.domain` | JPA/domain model for the flag aggregate |
| `FeatureFlagRepository` | `com.ffaas.repository` | Spring Data repository (`findByKey`, paging) |

## Configuration

| Variable / property | Default | Notes |
|---------------------|---------|-------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/ffaas` | Managed Postgres example: `jdbc:postgresql://host:25060/db?sslmode=require` |
| `DB_USER` | `ffaas` | |
| `DB_PASSWORD` | `ffaas` | |
| `ffaas.cache.flag-ttl` | `60s` | L1 TTL (`CacheProperties.flagTtl`) |
| `ffaas.cache.flag-max-size` | `10000` | L1 max entries |
| `ffaas.cache.evaluation-ttl` | `30s` | L2 TTL |
| `ffaas.cache.evaluation-max-size` | `10000` | L2 max entries |

## Deploying to DigitalOcean

1. Create a **Managed PostgreSQL** database.
2. Create an **App Platform** app from this repo; choose **Dockerfile** build (no Java buildpack required).
3. Attach the managed database and set env vars from bindable variables, for example:
   - `DB_URL=jdbc:postgresql://${db.HOSTNAME}:${db.PORT}/${db.DATABASE}?sslmode=require`
   - `DB_USER=${db.USERNAME}` / `DB_PASSWORD=${db.PASSWORD}`
   - (Replace `db` with your App Platform database component name.)
4. Health check path: `/actuator/health` on port `8080`.
5. **Instance count must be 1** — L1/L2 are in-process Caffeine caches; write-through eviction is process-local, so multiple instances can serve stale results after a write. See [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-known-limitations--future-work).
6. Flyway runs on startup (`ddl-auto: validate`); ensure the DB is reachable before the first deploy.

Live app: https://plankton-app-mjcf9.ondigitalocean.app

## Known limitations

See [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-known-limitations--future-work) for the full list. Highlights:

- **Single-instance cache** — write-through eviction is process-local; multi-instance needs pub/sub invalidation or TTL-only staleness.
- **No auth / rate limiting** — front with a gateway in production.
- **H2 for repository unit tests** — JSONB behavior can differ from Postgres.
