# PRD — Feature Flag as a Service (ffaas)

A REST API service that stores feature flags, manages flag state, and dynamically evaluates feature availability based on user context attributes.

- **Stack:** Java 21, Spring Boot 3.x, Maven, PostgreSQL (Docker), Caffeine cache
- **Repo:** `digitalocean-ffaas`

---

## 1. Goals

1. **Flag creation & storage** — CRUD for feature flags: unique key, name, description, global enabled toggle, default state, and an ordered list of targeting rules.
2. **Contextual evaluation** — An evaluation endpoint accepts a user context (`userId`, plus arbitrary attributes like `subscriptionTier`, `region`) and returns ON/OFF decided by the flag's rules against that context — not just the global toggle.
3. **Caching** — In-memory caching so hot evaluation paths avoid database lookups; cache is invalidated on flag writes.
4. **Production-grade hygiene** — strict request validation, consistent error model, graceful fallbacks, unit tests, CI via GitHub Actions, Dockerized deploy.

### Non-goals (out of scope)

- Authentication / authorization / multi-tenancy
- User-context management APIs (context is supplied *in* each evaluate call, never stored)
- Flag audit history, scheduled changes, streaming/webhook updates
- Multi-instance distributed cache coherence (single-instance assumption; documented as a known limitation)

---

## 2. Domain Model

### FeatureFlag
| Field | Type | Notes |
|---|---|---|
| `key` | string | Unique identifier. Regex `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$` (3–64 chars, lowercase alnum, `-`/`_` inside). Immutable after creation. |
| `name` | string | Human name, 1–100 chars, required. |
| `description` | string | Optional, ≤ 500 chars. |
| `enabled` | boolean | Global kill switch. `false` ⇒ evaluation always returns OFF regardless of rules. |
| `defaultState` | boolean | Result when no rule matches. |
| `rules` | Rule[] | Ordered targeting rules (may be empty). |
| `createdAt` / `updatedAt` | timestamp | Server-managed. |

### Rule
| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Server-generated. |
| `priority` | int | ≥ 0, **unique within a flag**. Lower value = evaluated first. |
| `conditions` | Condition[] | 1–20 conditions, **AND-ed** together. |
| `serve` | boolean | State to return when this rule matches (usually `true`). |
| `rolloutPercentage` | int \| null | **Extension feature.** `null`/absent ⇒ rule applies to 100% of matching users (core flow). If set: 0–100, deterministic per-user bucketing (see §4.3). |

### Condition
| Field | Type | Notes |
|---|---|---|
| `attribute` | string | Context attribute name, e.g. `subscriptionTier`, `region`, `userId`. 1–64 chars. |
| `operator` | enum | `EQ`, `NEQ`, `IN`, `NOT_IN`, `GT`, `LT` |
| `value` | scalar or array | `EQ`/`NEQ`: string/number/boolean. `IN`/`NOT_IN`: non-empty array of scalars. `GT`/`LT`: number only. |

---

## 3. API Contract

Base path: `/api/v1`. All bodies are JSON.

### 3.1 Create flag — `POST /api/v1/flags`

Request:
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

Responses: `201` with the created flag (incl. rule ids, timestamps) + `Location` header; `400` validation failure; `409` duplicate key.

### 3.2 Get flag — `GET /api/v1/flags/{key}` → `200` flag \| `404`

### 3.3 List flags — `GET /api/v1/flags?page=0&size=20` → `200` `{ "items": [...], "page": 0, "size": 20, "totalItems": 42 }`

### 3.4 Update flag — `PUT /api/v1/flags/{key}`

Full replace of mutable fields (`name`, `description`, `enabled`, `defaultState`, `rules`); `key` immutable (key in body, if present, must match path else `400`). Responses: `200` updated flag; `400`; `404`. **Must evict cache entries for this key.**

### 3.5 Delete flag — `DELETE /api/v1/flags/{key}` → `204` \| `404`. **Must evict cache entries for this key.**

### 3.6 Evaluate — `POST /api/v1/flags/{key}/evaluate`

Request:
```json
{
  "userId": "user-123",
  "attributes": {
    "subscriptionTier": "premium",
    "region": "us-east",
    "age": 34
  }
}
```
- `userId`: required, non-blank, ≤ 128 chars.
- `attributes`: optional object; values must be scalars (string/number/boolean); ≤ 50 entries. `userId` is also available to conditions as attribute `userId`.

Response `200`:
```json
{
  "flagKey": "premium-dashboard",
  "enabled": true,
  "reason": "RULE_MATCH",
  "matchedRuleId": "0d5a7c1e-..."
}
```

| `reason` | Meaning | `enabled` | `matchedRuleId` |
|---|---|---|---|
| `FLAG_DISABLED` | Global toggle off | `false` | `null` |
| `RULE_MATCH` | A rule matched (and rollout, if set, included the user) | rule's `serve` | rule id |
| `ROLLOUT_EXCLUDED` | Rule matched but user outside rollout percentage *(extension)* | flag's `defaultState` | rule id |
| `DEFAULT` | No rule matched | flag's `defaultState` | `null` |

Errors: `400` (missing userId, non-scalar attributes), `404` unknown flag, `503` DB unavailable and no cached definition (see §5).

---

## 4. Evaluation Semantics

### 4.1 Algorithm (core)
1. If flag `enabled == false` → `FLAG_DISABLED`, result `false`. Stop.
2. Iterate rules by ascending `priority`. A rule matches iff **all** its conditions match the context (AND). Rules are alternatives (OR) — **first match wins**.
3. On match → result = rule's `serve`, reason `RULE_MATCH`.
4. No rule matches → result = `defaultState`, reason `DEFAULT`.

### 4.2 Condition matching
- Missing attribute in context ⇒ condition is **false** (never throws).
- `EQ`/`NEQ`: type-aware equality (numbers compared numerically: `34 == 34.0`).
- `IN`/`NOT_IN`: membership in the value array.
- `GT`/`LT`: numeric comparison; if the context value is not a number ⇒ false.

### 4.3 Percentage rollout (extension — additive only)
- Applies only when a matched rule has non-null `rolloutPercentage`.
- Bucket = `abs(murmur3_32(flagKey + ":" + userId)) % 100`. User included iff `bucket < rolloutPercentage`.
- Deterministic: same user + flag always gets the same result; independent across flags.
- Excluded user → reason `ROLLOUT_EXCLUDED`, result = `defaultState`.

---

## 5. Caching & Fallback Requirements

- **L1 — flag definition cache** (Caffeine): key = flag key, value = full flag + rules. Populated on read-miss. TTL 60s (staleness safety net). **Evicted on update/delete of that key** (write-through invalidation).
- **L2 — evaluation result cache** (Caffeine): key = `flagKey + hash(normalized context)`, value = evaluation response. TTL 30s, max 10 000 entries. **All entries for a flag evicted on that flag's update/delete.**
- **Graceful fallback:** if the DB is unreachable during evaluation and L1 holds the flag (even expired-stale, if retrievable), serve from cache and log a warning; otherwise return `503` with the standard error body. CRUD endpoints do not fall back — they surface `503`.
- Correctness rule: results are computed per-context; the L2 cache must never return a result computed for a different context (hash must cover all attributes + userId).

---

## 6. Validation & Error Model

All errors use one envelope:
```json
{
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "details": [ { "field": "rules[0].conditions[1].value", "issue": "IN operator requires a non-empty array" } ],
  "timestamp": "2026-07-18T10:15:00Z",
  "path": "/api/v1/flags"
}
```
`error` codes: `VALIDATION_FAILED` (400), `MALFORMED_JSON` (400), `FLAG_NOT_FOUND` (404), `DUPLICATE_KEY` (409), `INTERNAL_ERROR` (500), `SERVICE_UNAVAILABLE` (503).

Validation highlights (reject-early, field-level messages):
- key regex; name length; ≤ 50 rules per flag; priorities unique per flag; 1–20 conditions per rule
- operator/value compatibility (§2 Condition table); `rolloutPercentage` ∈ [0,100] when present
- evaluate: `userId` required; attribute values scalar only
- Unknown JSON fields rejected (`400 MALFORMED_JSON` or `VALIDATION_FAILED`)

---

## 7. Quality Requirements

- **Unit tests** (JUnit 5 + Mockito): rule engine (every operator, missing attributes, priority ordering, first-match-wins), rollout determinism/boundaries (0 and 100), services with mocked repositories, cache hit/miss/eviction/fallback, validation, error handler. Repository tests on H2.
- **CI:** GitHub Actions on push/PR — `mvn verify` (build + all tests). Java 21, dependency cache.
- **Runability:** `docker compose up` starts Postgres + app; multi-stage Dockerfile; README with curl examples and DigitalOcean App Platform deploy notes.
- **Observability (lightweight):** Spring Boot actuator health endpoint; structured log line per evaluation at DEBUG, cache stats via Caffeine `recordStats`.