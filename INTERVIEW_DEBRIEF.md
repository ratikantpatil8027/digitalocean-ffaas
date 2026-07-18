# ffaas — Complete Exercise Debrief & Interview Prep

**Project:** Feature Flag as a Service (`digitalocean-ffaas`)
**Live demo:** https://plankton-app-mjcf9.ondigitalocean.app (health: `/actuator/health`)
**Built:** 2026-07-18, one time-boxed day. Java 21 · Spring Boot 3 · PostgreSQL (DO Managed) · Caffeine · GitHub Actions · DigitalOcean App Platform.

This is my personal walkthrough document: what I built, every decision and its tradeoff, how I ran the process, where I got stuck, and how I'd defend each choice.

---

## 1. Elevator pitch (30 seconds)

A REST service that stores feature flags with targeting rules and evaluates them **per-request against a user context** (userId, subscriptionTier, region, …) — not just a global toggle. A priority-ordered rule engine decides ON/OFF with an explainable `reason` code; a two-layer in-process cache makes warm evaluations complete with **zero database round-trips**; writes invalidate the cache immediately. Production hygiene throughout: strict validation, one error envelope, graceful DB-down fallback, unit tests per layer, CI, Docker, deployed on DigitalOcean App Platform against Managed Postgres.

---

## 2. Requirements → what I shipped

| Requirement | Delivered |
|---|---|
| Creation & storage (name, default state, rules) | Full CRUD `/api/v1/flags` — key, name, description, global `enabled`, `defaultState`, ordered rules with conditions |
| Contextual evaluation | `POST /api/v1/flags/{key}/evaluate` — rule engine over user context, response includes `reason` + `matchedRuleId` |
| In-memory cache preventing DB lookups | Two Caffeine layers (definitions + results), write-through invalidation, warm path = 0 DB hits |
| Production-grade architecture & code | Layered design, pure rule engine, validation, error model, fallbacks, per-layer unit tests |
| CI/CD | GitHub Actions `mvn verify` on push/PR; auto-deploy on push via DO App Platform |
| Extension: percentage rollout | Per-rule `rolloutPercentage`, deterministic `hash(flagKey:userId) % 100`, purely additive |

Deliberate **non-goals** (stated up front, not discovered later): auth, user-context storage APIs, multi-instance cache coherence, audit history.

---

## 3. High-Level Design

```
Client ──> api (controllers, DTOs, error handler)
              ├─> FlagService ──────────────┐ (CRUD + cache eviction)
              └─> EvaluationService         │
                     │  1. L2 result cache  │
                     │  2. L1 flag cache    │
                     │  3. repository ──> PostgreSQL
                     └─> RuleEvaluator (pure, framework-free)
```

**Layering** (`com.ffaas`): `api` → `service` → (`engine`, `cache`, `repository`) → `domain`. Dependency arrows point one way. The **engine has zero Spring/JPA imports** — it takes a flag definition + a `Map<String,Object>` context and returns an outcome. That one constraint made it the most heavily unit-tested and most defensible part of the codebase.

**Evaluate lifecycle (hot path):** Bean Validation → L2 result-cache lookup (hit ⇒ return, zero work) → L1 flag-cache lookup (miss ⇒ DB load, populate L1) → global-disabled short-circuit → rules by priority, first full match wins → optional rollout bucket → store in L2 → respond.

**Write path:** validate → transactional persist → **after commit** evict L1 entry + all L2 entries for that flag. Eviction after commit so a concurrent reader can't re-cache pre-commit state; eviction skipped on rollback.

---

## 4. Low-Level Design

### 4.1 Data model (Flyway `V1__init.sql`)

```sql
feature_flags(id UUID PK, key VARCHAR(64) UNIQUE, name, description,
              enabled BOOL, default_state BOOL, created_at, updated_at)

flag_rules(id UUID PK, flag_id FK → feature_flags ON DELETE CASCADE,
           priority INT CHECK >= 0, serve BOOL,
           rollout_percentage INT NULL CHECK 0..100,
           conditions JSONB NOT NULL,
           UNIQUE(flag_id, priority))
```

**Why conditions as JSONB, not a third table:** condition values are heterogeneous (string/number/boolean/array), conditions are always read and written *with their rule* as one unit, and nothing queries them independently — a normalized table adds joins for zero query benefit. The flag + rules load as one aggregate, ordered by priority; that aggregate is exactly what L1 caches. Mapped via a Jackson `AttributeConverter` to `List<Condition>`.

### 4.2 Rule engine semantics

- Rules sorted by ascending `priority`; **first match wins** (rules are OR-alternatives).
- Within a rule, **all conditions must match** (AND).
- Operators: `EQ`, `NEQ` (type-aware; numbers compared numerically so `34 == 34.0`), `IN`, `NOT_IN` (membership), `GT`, `LT` (numbers only).
- **Missing attribute ⇒ condition is false — never an error.** Bad data can't break evaluation.
- No match ⇒ `defaultState`, reason `DEFAULT`. Global `enabled=false` short-circuits to `FLAG_DISABLED`.
- Every response says *why*: `reason` ∈ `RULE_MATCH | ROLLOUT_EXCLUDED | DEFAULT | FLAG_DISABLED` + `matchedRuleId`. This is the observability/debuggability story — support can answer "why does user X see feature Y" from the response alone.

### 4.3 Rollout (extension, additive-only)

- `rolloutPercentage` nullable on a rule; `null` ⇒ rule applies to 100% (core flow untouched — this was an explicit requirement I enforced in the tracker: "no existing test assertion may change").
- Bucket = `abs(murmur3_32(flagKey + ":" + userId)) % 100`; included iff `bucket < percentage`.
- **Deterministic and stateless**: same user + flag always same result, no assignment storage, independent buckets per flag (flagKey in the hash).
- Excluded ⇒ `ROLLOUT_EXCLUDED` with `defaultState`, and evaluation **stops** (no fall-through to lower-priority rules — a deliberate semantic choice: the highest-priority matching rule owns the decision).

### 4.4 Cache design (the most-probed part — know this cold)

| | L1 — flag definitions | L2 — evaluation results |
|---|---|---|
| Key | `flagKey` | `flagKey + ":" + SHA-256(normalized context)` |
| Value | immutable flag+rules snapshot | full evaluation response |
| TTL | 60s (staleness safety net) | 30s |
| Max size | 10 000 | 10 000 |
| Invalidation | evict on update/delete | evict-all-for-flag on update/delete (per-flag key index) |

- **Context normalization is typed**: userId + attributes sorted by name, values tagged by type (`s:premium` vs `n:34` vs `b:true`) — so `"34"` and `34` hash differently. Correctness invariant: a cached result must never be served for a different context.
- **Results are computed per context, never shared** — caching evaluation *results* keyed by context hash is safe only because the hash covers everything the computation reads.
- **Graceful fallback:** DB unreachable during evaluate + L1 has the flag ⇒ serve stale, log WARN. L1 cold ⇒ 503 with the standard envelope. CRUD never falls back — writes fail loudly.
- TTLs and sizes are `@ConfigurationProperties`, not hardcoded.

### 4.5 Validation & error model

One envelope everywhere: `{status, error, message, details[{field, issue}], timestamp, path}`. Codes: `VALIDATION_FAILED`, `MALFORMED_JSON`, `FLAG_NOT_FOUND`, `DUPLICATE_KEY`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE`.

- Key regex `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$`; key immutable after create (body key must match path on PUT).
- Custom class-level validator: unique priorities per flag; **operator/value compatibility** (IN needs non-empty array, GT/LT numeric) with precise paths like `rules[0].conditions[1].value`.
- Evaluate: `userId` required; attribute values scalar-only; unknown JSON fields rejected (Jackson fail-on-unknown).
- Duplicate-key race guarded twice: `existsByKey` check *and* catching `DataIntegrityViolationException` from the DB unique constraint → 409.

### 4.6 Key classes

| Class | Layer | Responsibility |
|---|---|---|
| `FlagController`, `EvaluationController` | api | HTTP only — no business logic |
| `GlobalExceptionHandler` | api | every failure → the one envelope |
| `CreateFlagRequest`, `EvaluateRequest/Response`, `ErrorResponse` | api/dto | records; entities never exposed |
| `FlagService` | service | transactional CRUD + post-commit cache eviction |
| `EvaluationService` | service | L2 → L1 → DB orchestration + fallback |
| `RuleEvaluator`, `RolloutBucketer`, `EvaluationOutcome` | engine | pure decision logic, framework-free |
| `FlagCache` (L1), `EvaluationResultCache` (L2) | cache | Caffeine wrappers owning key schemes + eviction API |
| `FeatureFlag`, `Rule`, `Condition`, `Operator` | domain | aggregate; conditions via Jackson converter |
| `FeatureFlagRepository` | repository | Spring Data JPA, `findByKey` etc. |

---

## 5. Decisions & tradeoffs (with the losing options)

| Decision | Chosen | Rejected & why |
|---|---|---|
| Stack | Java 21 + Spring Boot 3 + Maven | Node/TS, FastAPI, Go — Spring chosen for production-grade optics and my fluency; cost was ceremony under time pressure |
| Storage | Managed PostgreSQL via JPA + Flyway | SQLite (not production-realistic), Mongo (no stronger justification for extra infra) |
| Conditions storage | JSONB array on the rule row | third normalized table — joins for no query benefit |
| Rule expressiveness | flat conditions, AND-in-rule / OR-across-rules, first match wins | full boolean expression trees — validation and test burden explodes; equality-only — too shallow |
| Rollout placement | **per-rule** percentage | flag-level — can't express "50% of premium users"; per-rule composes with targeting |
| Rollout mechanism | stateless hash bucketing | stored per-user assignments — needs a table, migrations, cleanup; hash gives determinism for free |
| Cache | **in-process Caffeine, two layers** | Redis — relocates the lookup instead of eliminating it (network hop per evaluation); this is how LaunchDarkly/Unleash SDKs work too (in-memory rule copy) |
| Invalidation | write-through eviction + TTL safety net | TTL-only — flag flips take up to TTL to propagate; result caching without typed context hashing — cross-context leakage risk |
| Consistency vs scale | **instant propagation, pinned to 1 instance** | N instances with TTL-bounded staleness — valid, documented as the relaxation lever; not chosen because instant flips demo better and scale wasn't required |
| Tests | unit-only, H2 for repositories | Testcontainers integration — better fidelity (H2 JSONB ≠ Postgres JSONB — a known, admitted gap), cut for time |
| Read replica on DO | **No** | the cache *is* the read-offload; a replica adds cost + replication-lag inconsistency while relieving no actual load |
| Deploy | App Platform + Dockerfile | Droplet/K8s — undifferentiated ops work for this scope |

**The one-liner I'd lead with on caching:** "In-process caching is the industry-standard hot path for flag evaluation; single-instance is a *consequence of choosing instant invalidation over staleness tolerance*, and the scale-out path — eviction broadcasting via Redis pub/sub or Postgres LISTEN/NOTIFY, not cache externalization — is documented in the architecture."

---

## 6. Process: how I actually built it

### 6.1 Two-agent split

- **Architecture agent (Claude, this session):** requirements grilling, PRD, architecture doc, decision log, work breakdown, prompt engineering, DO troubleshooting. Never touched the code.
- **Coding agent (separate workspace):** executed self-contained prompts, one vertical slice at a time, TDD.

Why the split works: the architecture session holds all context and produces *documents as the interface* (PRD.md, ARCHITECTURE.md, DECISIONS.md live in the repo root); each coding session starts fresh, reads those documents, and can't drift because every prompt restates its contract and acceptance criteria.

### 6.2 Pipeline

1. **Requirements grilling** — structured Q&A locking: stack, storage, rule expressiveness, cache design (I chose two layers over the recommended one — see §7), evaluate API shape, rollout semantics (**my correction:** rollout must be a purely additive extension; core create→evaluate works for all users without it), test depth, deliverable format.
2. **PRD.md** — full contract with JSON examples, validation rules, error model, non-goals.
3. **ARCHITECTURE.md** — layers, lifecycle diagrams, schema, cache invalidation matrix, tradeoffs, known limitations. Doubles as repo documentation.
4. **Tracer-bullet breakdown** — 8 vertical slices in TRACKER.md + one issue file each, with **acceptance criteria written as literal test names** and blocking edges reflecting *real* dependencies (rule engine only needed the domain model, not the CRUD API — so 03 and 04 could parallelize).
5. **Per-bullet loop in the coding agent:** context setup prompt (once) → `/implement-with-tdd` (red tests from the issue's test names → green → commit) → `/code-review` (standards axis + spec axis) → `/qa` (findings → fix tasks → full suite) → `/clear` + `/clear-and-reload` (fresh context rehydrated from tracker + PRD only) → next bullet.
6. **Tracker as ground truth** — statuses, test results, and hotfix notes logged so any fresh session knows exactly where things stand.

### 6.3 The 8 bullets

| NN | Bullet | Blocked by |
|----|--------|-----------|
| 01 | Scaffold (Boot 3, compose Postgres, H2 test profile, health) | — |
| 02 | Domain + persistence (entities, Flyway V1, repo + H2 tests) | 01 |
| 03 | Flag CRUD API (DTOs, validators, error handler) | 02 |
| 04 | Rule engine, core (pure; no rollout yet) | 02 |
| 05 | Evaluation endpoint (wires 03+04, reason codes) | 03, 04 |
| 06 | Two-layer cache + DB-down fallback | 05 |
| 07 | Percentage rollout (additive; existing tests untouched) | 06 |
| 08 | CI + Docker compose app + enriched README | 03–07 |

Deploy milestones interleaved: first DO deploy after 03 (CRUD demoable), redeploy after 06 (full product), docs finalized in 08.

### 6.4 Testing strategy

- **Engine** — the deepest suite: every operator × (match / non-match / type-mismatch / missing attribute), numeric coercion across boxing, AND/OR semantics, priority ordering, first-match-wins, disabled-flag short-circuit, empty rules. Rollout: determinism over repeated calls, 0/100 boundaries, ~30%±3 distribution over 10k users, no fall-through on exclusion, no cross-user leakage through the result cache.
- **Services** — Mockito: cache-hit paths never touch the repository; both-miss path hits it exactly once; eviction on write; both fallback branches (stale-serve and 503).
- **Cache** — fake Ticker for TTL expiry; typed context hashing (`34` vs `"34"` produce different keys); per-flag eviction purges the key index.
- **API** — `@WebMvcTest`: every error path produces the exact envelope + status.
- **Repository** — `@DataJpaTest` on H2: aggregate round-trip, cascade delete, both unique constraints.
- **Live verification** — a curl suite against the deployed app after each milestone; the decisive test: warm the cache with a `RULE_MATCH`, flip the flag off via PUT, re-evaluate the same context **immediately** → `FLAG_DISABLED` with no TTL wait proves write-through invalidation end-to-end.

---

## 7. DigitalOcean setup — the full journey, stumbles included

1. **Created Managed PostgreSQL** first, linked to the project.
2. **First app creation failed: "No components detected."** Root cause: **DO App Platform has no Java buildpack** (only Node/Python/Go/PHP/Ruby/static) — `pom.xml` means nothing to it; a Dockerfile is mandatory for Java. Fix: pulled the multi-stage Dockerfile forward from bullet 08 as a logged hotfix (build stage `maven:3.9-eclipse-temurin-21` with `pom.xml`-first copy for layer caching; runtime `21-jre-alpine`, non-root user, HEALTHCHECK).
3. **App config choices:**
   - **Containers 2 → 1** — not cost, *correctness*: write-through eviction is process-local; with 2 containers a flag flip through container A leaves container B stale for up to 60s → same request, different answers depending on the load balancer. Pinned to 1 per the architecture.
   - HTTP port 8080; health check set to HTTP `/actuator/health` (not the default TCP check, which passes the moment the port opens even if Spring is broken); generous initial delay for Flyway + Spring startup.
   - **Env vars at component level** (least privilege — only this service needs them), **run-time-only scope** (the Docker build never touches the DB), `DB_PASSWORD`/`DB_URL` encrypted.
   - **Attached the managed DB** to the app — auto-registers the app in the DB's Trusted Sources firewall and exposes bindable variables. Declined "Create dev database" (would have made a second, wrong DB). Skipped read replica and app-level env vars as overkill.
4. **First deploy failed: Flyway "Unable to obtain connection… The connection attempt failed."** Diagnosis discipline: this is *network-level* (auth failure reads "password authentication failed"; malformed URL reads "Driver claims to not accept jdbcUrl"). Suspects in order: Trusted Sources pairing, private-vs-public hostname, URL typos. **Fix that ended it: switched from hand-pasted values to bindable variables**, letting DO compose the connection from the attachment itself:
   ```
   DB_URL      = jdbc:postgresql://${production-database.HOSTNAME}:${production-database.PORT}/${production-database.DATABASE}?sslmode=require
   DB_USER     = ${production-database.USERNAME}
   DB_PASSWORD = ${production-database.PASSWORD}
   ```
   Rules learned: `jdbc:` prefix mandatory (DO's copyable string is `postgresql://user:pass@host` — Spring rejects it); credentials never embedded in the URL; port **25060** (DO's pooled port, not 5432); `sslmode=require` always. Bindables also auto-rotate with the DB password. Deleted the auto-added `DATABASE_URL` bindable the app never reads.
5. **Zero code changes for deploy** — by design. `application.yml` reads `${DB_URL:local-default}` etc., so cloud config was pure configuration. Success signature in runtime logs: Flyway `Migrating schema "public" to version "1 - init"` → Tomcat on 8080 → health green.
6. **Found a real bug via the deployment:** hitting `/health` (wrong path) returned **500**, not 404 — Spring Boot 3.2+ throws `NoResourceFoundException` for unmapped paths, and my catch-all `Exception → 500` handler swallows it before default 404 handling. Hotfix specced (dedicated handler → 404 envelope, before the catch-all). *An overly-greedy catch-all converts client errors into server errors — found it by manually probing the live app, which no unit test would have caught since tests only exercised mapped routes.*

---

## 8. Where I got stuck / what I'd own honestly

1. **"No components detected"** — didn't know DO lacks a Java buildpack; cost one deploy cycle. Lesson: verify platform runtime support before scheduling containerization last.
2. **DB connection failure on first real deploy** — hand-pasted connection values are fragile (private hostname, truncated paste, missing `jdbc:`). Lesson: prefer platform-managed bindings over copy-paste config; read the *exception class* to classify the failure (network vs auth vs format) before touching anything.
3. **The `/health` 500 bug** — my own error-handler design flaw, surfaced only by probing the live app off the happy path. Lesson: catch-alls need explicit carve-outs for framework 404s; and "test the deployed thing with wrong inputs" finds what unit tests structurally can't.
4. **Two-layer cache against the "keep it simple" brief** — I chose the elaborate option (L1 + L2) when one layer was the recommendation. It worked, but it was the riskiest bullet for agent drift and I mitigated by making its test list highly prescriptive. If I re-ran it under tighter time, I'd ship L1-only and add L2 as an extension bullet like rollout.
5. **Initial container count 2** — DO's default; I nearly shipped a consistency bug the architecture explicitly rules out. Caught because the doc said "single-instance" — the value of writing constraints down is that they catch *you* later.

---

## 9. Likely follow-up questions — my answers

- **"Why not Redis?"** Redis relocates the lookup (network hop each evaluation) instead of eliminating it; the requirement was preventing lookups on the hot path. Real flag SDKs evaluate against in-memory rule copies for the same reason. Redis enters my roadmap as the *eviction broadcast channel* (pub/sub) for multi-instance — not as the cache.
- **"How do you scale past one instance?"** Three levers, in cost order: (a) accept TTL-bounded staleness — zero code, N instances today, flips propagate ≤60s; (b) keep instant propagation by broadcasting evictions (Redis pub/sub or Postgres LISTEN/NOTIFY); (c) externalize the cache (last resort — gives back the zero-hop property).
- **"What if two requests race a flag update?"** Eviction runs after commit, so readers either see the old committed state (and get evicted next) or reload the new state; nothing can re-cache pre-commit data. Within one instance, staleness after a write is zero.
- **"Why is the rollout hash keyed by flagKey + userId?"** userId alone would put a user in the same bucket for *every* flag (correlated rollouts — user in the unlucky 10% for everything); flagKey decorrelates.
- **"Cache stampede on a hot flag after eviction?"** Caffeine's loading-cache semantics coalesce concurrent loads per key; worst case is one DB read per flag per eviction — acceptable at this scale, worth `refreshAfterWrite` if it ever isn't.
- **"Why does ROLLOUT_EXCLUDED not fall through to the next rule?"** The highest-priority matching rule owns the decision; fall-through would make percentage rollout silently change *which rule* governs a user, which is unexplainable to operators. Explicitness beats cleverness in flag semantics.
- **"Biggest known weakness?"** H2-tested repositories vs Postgres JSONB — a fidelity gap I'd close first with Testcontainers. Second: no auth on a public demo API (would front with API keys). Both are in the README's known-limitations list — the point is that they're *chosen* debts, not blind spots.
- **"What did the coding agents do vs you?"** I owned every decision, contract, and tradeoff (this document is the proof); agents executed prescriptive, test-anchored prompts one slice at a time. The discipline that made it work: documents as the interface, acceptance criteria as literal test names, fresh context per slice, and a tracker as shared ground truth — plus manual live verification after every milestone, which is how I caught the 500 bug the tests missed.

---

## 10. If I had another day

1. Testcontainers integration tests (real Postgres, full request lifecycle) — closes the H2/JSONB gap.
2. Land the 404 hotfix; add springdoc-openapi (`/swagger-ui.html`) and a cache-stats endpoint (Caffeine `recordStats` is already on).
3. API-key auth middleware on mutating endpoints.
4. Bulk evaluation endpoint (`POST /evaluate` for all flags × one context — how real SDKs bootstrap).
5. Eviction broadcasting via Postgres LISTEN/NOTIFY → unlock horizontal scaling with zero new infra.
6. Flag audit log (who changed what, when) — first thing real operators ask for.