# PROMPTS — ordered execution prompts for the coding agent

**How to use:** First copy `PRD.md` and `ARCHITECTURE.md` into the repo root — the prompts reference them. Then feed prompts 1→8 to your coding agent **in order**, one at a time. Each prompt is self-contained, ends with acceptance criteria, and leaves the build green (`mvn verify` passes after every prompt). Commit after each prompt.

---

## Prompt 1 — Project scaffold

```
You are building "ffaas" — a Feature Flag as a Service REST API. Java 21, Spring Boot 3.x, Maven. The repo root contains PRD.md and ARCHITECTURE.md; read both before writing code and follow them as the source of truth.

Task: scaffold the project skeleton. No business logic yet.

1. Create a Maven Spring Boot 3 project, group `com.ffaas`, artifact `ffaas`, Java 21. Dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-actuator, postgresql (runtime), flyway-core + flyway-database-postgresql, com.github.ben-manes.caffeine:caffeine, spring-boot-starter-test (test), h2 (test scope), mockito (via starter-test).
2. Package structure under `com.ffaas`: `api`, `service`, `engine`, `cache`, `repository`, `domain`, `config` (empty packages are fine; add package-info or a placeholder only where needed).
3. `application.yml`: Postgres datasource via env vars with local defaults (DB_URL=jdbc:postgresql://localhost:5432/ffaas, DB_USER=ffaas, DB_PASSWORD=ffaas), Flyway enabled, JPA ddl-auto=validate, actuator exposes health only. Add `application-test.yml` activating H2 (PostgreSQL compatibility mode: `jdbc:h2:mem:ffaas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`) with Flyway enabled.
4. `docker-compose.yml` at repo root: `postgres:16-alpine` with db/user/password `ffaas`, port 5432, healthcheck, named volume.
5. Configure Jackson: fail on unknown properties, write dates as ISO-8601.
6. One smoke test: Spring context loads under the `test` profile.

Acceptance criteria (verify all before finishing):
- `mvn -B verify` passes (context-load test green, no Docker needed for tests).
- `docker compose up -d` then `mvn spring-boot:run` starts the app; `GET /actuator/health` returns `{"status":"UP"}`.
- No business endpoints exist yet.
```

---

## Prompt 2 — Domain model + persistence

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. PRD.md and ARCHITECTURE.md in repo root are the source of truth (see PRD §2 domain model, ARCHITECTURE §5 schema). Scaffold from the previous step exists: packages com.ffaas.{api,service,engine,cache,repository,domain,config}, Flyway configured, H2 test profile.

Task: implement the persistence layer.

1. Flyway migration `V1__init.sql` exactly per ARCHITECTURE.md §5: `feature_flags` table (id UUID PK, key varchar(64) unique not null, name varchar(100) not null, description varchar(500), enabled boolean, default_state boolean, created_at/updated_at timestamptz) and `flag_rules` (id UUID PK, flag_id FK cascade delete, priority int >= 0, serve boolean, rollout_percentage int null check 0–100, conditions JSONB not null, unique(flag_id, priority)); index on flag_id. Use `JSONB` — for H2 compatibility in tests, `JSON` via H2's PostgreSQL mode is acceptable if JSONB fails; keep the migration Postgres-first.
2. Entities in `com.ffaas.domain`:
   - `FeatureFlag`: fields per table; `@OneToMany(cascade = ALL, orphanRemoval = true)` rules, eagerly fetched, ordered by priority (`@OrderBy("priority ASC")`); `createdAt`/`updatedAt` managed via `@PrePersist`/`@PreUpdate`.
   - `Rule`: fields per table; `conditions` stored as a JSON string column mapped to `List<Condition>` via a JPA `AttributeConverter` using Jackson.
   - `Condition` (plain value class, not an entity): `attribute` (String), `operator` (enum `Operator`: EQ, NEQ, IN, NOT_IN, GT, LT), `value` (Object — scalar or list of scalars).
3. `FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID>` with `Optional<FeatureFlag> findByKey(String key)`, `boolean existsByKey(String key)`, `void deleteByKey(String key)`.
4. `@DataJpaTest` repository tests on H2: save flag with 2 rules and reload by key (rules ordered by priority, conditions round-trip through the converter intact), unique key constraint violation, cascade delete removes rules, unique (flag_id, priority) constraint enforced.

Acceptance criteria:
- `mvn -B verify` passes; all repository tests green on H2.
- Flyway migration applies cleanly against real Postgres (`docker compose up -d` + app start).
```

---

## Prompt 3 — Flag CRUD API

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. PRD.md (§3 API contract, §6 validation & error model) and ARCHITECTURE.md (§6) in repo root are the source of truth. Existing: domain entities (FeatureFlag, Rule, Condition, Operator), FeatureFlagRepository, Flyway schema.

Task: implement flag CRUD endpoints under /api/v1. (No evaluation, no caching yet — that comes later; FlagService talks straight to the repository for now.)

1. DTOs in `com.ffaas.api.dto` (records): `CreateFlagRequest`, `UpdateFlagRequest`, `RuleDto`, `ConditionDto`, `FlagResponse`, `PagedResponse<T>`, `ErrorResponse` + `FieldIssue`. Never expose entities. Error envelope fields exactly per PRD §6: status, error, message, details[{field, issue}], timestamp, path.
2. Validation:
   - Annotations: key matches `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$`; name @NotBlank @Size(max=100); description @Size(max=500); enabled/defaultState @NotNull; ≤ 50 rules; rule: priority @Min(0), serve @NotNull, 1–20 conditions, rolloutPercentage null or 0–100; condition: attribute @NotBlank @Size(max=64), operator @NotNull.
   - Custom class-level validator on the flag request: (a) rule priorities unique within the flag; (b) operator/value compatibility — EQ/NEQ: scalar (string/number/boolean); IN/NOT_IN: non-empty list of scalars; GT/LT: number. Violations report precise paths like `rules[0].conditions[1].value`.
3. Endpoints in `FlagController` per PRD §3: POST /api/v1/flags (201 + Location, 409 DUPLICATE_KEY on existing key), GET /api/v1/flags/{key} (200/404), GET /api/v1/flags?page&size (paged envelope: items/page/size/totalItems; default size 20, max 100), PUT /api/v1/flags/{key} (full replace of mutable fields; key immutable — body key, if present, must equal path key else 400; replace rules collection atomically), DELETE /api/v1/flags/{key} (204/404).
4. `FlagService` in `com.ffaas.service`: transactional CRUD, maps DTO↔entity, throws `FlagNotFoundException` / `DuplicateFlagKeyException` (also catch DataIntegrityViolationException on the unique key race → DuplicateFlagKeyException).
5. `GlobalExceptionHandler` (@RestControllerAdvice) in `com.ffaas.api` mapping exactly per ARCHITECTURE §6: MethodArgumentNotValidException→400 VALIDATION_FAILED with field details, HttpMessageNotReadableException→400 MALFORMED_JSON, FlagNotFoundException→404 FLAG_NOT_FOUND, DuplicateFlagKeyException→409 DUPLICATE_KEY, DataAccessException→503 SERVICE_UNAVAILABLE, Exception→500 INTERNAL_ERROR (generic message, log full trace server-side).
6. Tests: @WebMvcTest for the controller with mocked FlagService — happy paths per endpoint; validation failures return the exact envelope (assert status, error code, a details[].field path); 404/409/500 mappings. Mockito unit tests for FlagService (mocked repository): create, duplicate key, update not-found, delete.

Acceptance criteria:
- `mvn -B verify` passes.
- Manual check against `docker compose up -d` + running app: create the sample flag from PRD §3.1 via curl → 201; create again → 409; GET/PUT/DELETE behave per contract; invalid payload (e.g. IN with scalar value) → 400 with field-level details.
```

---

## Prompt 4 — Rule engine (core)

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. PRD.md §4 (evaluation semantics) and ARCHITECTURE.md §3 in repo root are the source of truth. Existing: domain model (FeatureFlag, Rule with priority/serve/conditions, Condition, Operator: EQ/NEQ/IN/NOT_IN/GT/LT), CRUD API.

Task: implement the pure rule engine in `com.ffaas.engine`. HARD CONSTRAINT: this package must have zero Spring/JPA/cache imports — plain Java operating on the domain objects and a `Map<String, Object>` context. Do NOT implement percentage rollout in this step — a matching rule applies to every user (ignore Rule.rolloutPercentage entirely for now).

1. `EvaluationOutcome` record: `boolean enabled`, `Reason reason` (enum: FLAG_DISABLED, RULE_MATCH, DEFAULT — leave room to add ROLLOUT_EXCLUDED later), `UUID matchedRuleId` (nullable).
2. `RuleEvaluator.evaluate(FeatureFlag flag, String userId, Map<String, Object> attributes)`:
   - Build the effective context = attributes + entry "userId"→userId (userId wins on collision).
   - flag.enabled == false → (false, FLAG_DISABLED, null).
   - Iterate rules by ascending priority; a rule matches iff ALL conditions match (AND); first matching rule wins (OR) → (rule.serve, RULE_MATCH, rule.id).
   - No match → (flag.defaultState, DEFAULT, null).
3. Condition matching semantics (never throw on bad/missing data — a non-matching condition is just false):
   - Attribute absent from context → false.
   - EQ/NEQ: type-aware equality; numbers compared numerically regardless of Integer/Long/Double boxing (34 equals 34.0); strings case-sensitive.
   - IN/NOT_IN: membership of the context value in the condition's list, same numeric-aware equality.
   - GT/LT: both sides must be numbers (context value may be a numeric string? NO — numbers only; non-number → false); compare as double.
4. Exhaustive JUnit 5 tests (no Spring context) — this should be the largest test class in the repo:
   - Every operator: match, non-match, type-mismatch, missing attribute.
   - Numeric equality across boxing (int vs double), IN with mixed scalars, NOT_IN with absent attribute (absent → condition false, per the rule above — test documents this explicitly).
   - AND within a rule (one failing condition fails the rule), OR across rules, priority ordering decides when multiple rules match, first-match-wins.
   - Disabled flag short-circuits even with matching rules; empty rules → DEFAULT with defaultState true and false; userId available as attribute "userId".

Acceptance criteria:
- `mvn -B verify` passes; engine tests cover all enumerated cases.
- `grep -r "org.springframework" src/main/java/com/ffaas/engine/` returns nothing.
```

---

## Prompt 5 — Evaluation endpoint

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. PRD.md §3.6 + §4 and ARCHITECTURE.md §2.1 in repo root are the source of truth. Existing: CRUD API with GlobalExceptionHandler, FlagService, FeatureFlagRepository, and a pure `com.ffaas.engine.RuleEvaluator` returning EvaluationOutcome{enabled, reason, matchedRuleId}. No caching yet (next step) — load flags via the repository directly.

Task: implement `POST /api/v1/flags/{key}/evaluate`.

1. DTOs: `EvaluateRequest` (userId: @NotBlank @Size(max=128); attributes: optional Map<String,Object>, default empty) and `EvaluateResponse` (flagKey, enabled, reason, matchedRuleId — reason serialized as the enum name string).
2. Custom validation: every attributes value must be a scalar (String/Number/Boolean — reject null values, nested maps, lists) and ≤ 50 entries; violations → 400 VALIDATION_FAILED with field paths like `attributes.someKey`.
3. `EvaluationService` in `com.ffaas.service`: load flag by key (miss → FlagNotFoundException → existing 404 handling) → call RuleEvaluator → map outcome to EvaluateResponse. Log one DEBUG line per evaluation: flagKey, userId, reason, matchedRuleId.
4. `EvaluationController` in `com.ffaas.api`.
5. Tests: Mockito unit tests for EvaluationService (mocked repository): rule-match path, default path, disabled-flag path, unknown flag. @WebMvcTest for the controller: 200 happy path asserting the full response shape, missing userId → 400, list-valued attribute → 400, unknown flag → 404.

Acceptance criteria:
- `mvn -B verify` passes.
- Manual: create PRD §3.1's sample flag, then evaluate with {"userId":"u1","attributes":{"subscriptionTier":"premium","region":"us-east"}} → enabled=true, reason=RULE_MATCH, matchedRuleId set; with tier "free" → enabled=false, reason=DEFAULT; set flag enabled=false via PUT → reason=FLAG_DISABLED.
```

---

## Prompt 6 — Caching layer (L1 + L2) with graceful fallback

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. ARCHITECTURE.md §4 (caching design + invalidation matrix) and PRD.md §5 in repo root are the source of truth. Existing: FlagService (CRUD, straight to repository), EvaluationService (loads flag via repository, calls pure RuleEvaluator), Caffeine already on the classpath.

Task: add the two-layer in-memory cache so warm evaluations hit the DB zero times, with write-through invalidation and a DB-down fallback.

1. `com.ffaas.cache.FlagCache` (L1): Caffeine `Cache<String, FeatureFlag>` — expireAfterWrite 60s, maximumSize 10_000, recordStats. API: `Optional<FeatureFlag> get(String key)`, `put`, `evict(String key)`. Cached values must be effectively immutable snapshots (detached from JPA; defensive-copy rules list).
2. `com.ffaas.cache.EvaluationResultCache` (L2): Caffeine cache — expireAfterWrite 30s, maximumSize 10_000, recordStats. Key = flagKey + ":" + SHA-256 hex of the normalized context (userId + attributes entries sorted by attribute name, values rendered with their type, e.g. s:premium vs n:34 vs b:true — so "34" and 34 hash differently). Maintain a per-flagKey index of live cache keys to support `evictAllForFlag(String flagKey)`; purge index entries on eviction.
3. TTLs and sizes come from `application.yml` (ffaas.cache.flag-ttl=60s etc.) via a `CacheConfig` @ConfigurationProperties class — no hardcoded numbers.
4. Wire into `EvaluationService` (order per ARCHITECTURE §2.1): L2 lookup → hit returns immediately; miss → L1 lookup → miss → repository load + L1.put → RuleEvaluator → L2.put → respond.
5. Graceful fallback in the L1-miss repository load: catch DataAccessException; if L1 has the flag, serve it and log WARN "db_unavailable_served_from_cache" with the flagKey; if not, rethrow (existing handler → 503 SERVICE_UNAVAILABLE). Note: with a single shared Caffeine instance the fallback covers the common case (entry still live); document in a comment that expired entries are not recoverable — acceptable per ARCHITECTURE §4.
6. Wire into `FlagService`: after successful update/delete commit, call `flagCache.evict(key)` and `evaluationResultCache.evictAllForFlag(key)` (use TransactionSynchronization afterCommit, or perform eviction after the transactional method returns — evictions must not fire on rollback).
7. Tests (JUnit 5 + Mockito; use Caffeine's `Ticker`/fake time where needed):
   - EvaluationService: L2 hit → repository never called; L2 miss + L1 hit → repository never called; both miss → one repository call, both caches populated (second identical call: zero repository calls).
   - Context isolation: same flag, different attributes → different L2 keys, both cached independently; typed hashing: attribute 34 (number) vs "34" (string) produce different keys.
   - Invalidation: update flag → next evaluation reloads from repository (L1 evicted) and recomputes (L2 evicted); delete flag → evaluation returns 404 even though caches were warm.
   - Fallback: repository throws DataAccessException with warm L1 → serves cached result; with cold L1 → 503 path (exception propagates).

Acceptance criteria:
- `mvn -B verify` passes; all cache behavior tests green.
- Manual: create flag, evaluate twice, `docker compose stop postgres`, evaluate again with the same context → still 200 (fallback); evaluate an uncached flag → 503; `docker compose start postgres` recovers.
```

---

## Prompt 7 — Extension: deterministic percentage rollout

```
Repo: ffaas — Spring Boot 3 / Java 21 feature-flag service. PRD.md §4.3 and ARCHITECTURE.md §3 in repo root are the source of truth. Existing: full CRUD + evaluation + two-layer cache; Rule already has a nullable rolloutPercentage column (0–100 DB check) and request validation; the engine currently ignores it. Reason enum currently: FLAG_DISABLED, RULE_MATCH, DEFAULT.

Task: activate percentage rollout as a purely ADDITIVE extension. Flags without rolloutPercentage must behave exactly as before — no existing test may change its assertion (adding tests is fine).

1. `com.ffaas.engine.RolloutBucketer` (pure, no Spring): `int bucket(String flagKey, String userId)` = abs(murmur3_32(flagKey + ":" + userId)) % 100. Use a well-tested MurmurHash3 32-bit implementation — add com.google.guava:guava and use Hashing.murmur3_32_fixed(), or inline a documented reference implementation if avoiding the dependency; handle Integer.MIN_VALUE in abs. Deterministic across JVM restarts (no default seeds that vary).
2. In RuleEvaluator: when the first matching rule has rolloutPercentage != null → included iff bucket(flagKey, userId) < rolloutPercentage. Included → (rule.serve, RULE_MATCH, rule.id) as before. Excluded → (flag.defaultState, ROLLOUT_EXCLUDED, rule.id) and evaluation STOPS (no fall-through to lower-priority rules). Add ROLLOUT_EXCLUDED to the Reason enum and API docs. rolloutPercentage == null → identical to pre-existing behavior.
3. Tests:
   - Determinism: same (flagKey, userId) → same bucket over 1000 calls; different flagKeys give a different bucket for the same user (spot-check several).
   - Boundaries: rolloutPercentage=0 → always ROLLOUT_EXCLUDED (even for matching contexts); =100 → always RULE_MATCH; null → RULE_MATCH (unchanged core behavior).
   - Distribution sanity: for 10_000 sequential userIds at 30% → inclusion within 27–33%.
   - No fall-through: two rules where user is excluded from rule priority 0's rollout but would match rule priority 1 → result is ROLLOUT_EXCLUDED with rule 0's id.
   - L2-cache compatibility: nothing to change — the result depends only on flagKey+userId+attributes, already fully in the L2 key; add one test asserting an excluded user's cached result doesn't leak to a different userId.

Acceptance criteria:
- `mvn -B verify` passes; every pre-existing test passes UNMODIFIED.
- Manual: create a flag whose rule has rolloutPercentage=30; evaluate ~10 distinct userIds → mixed RULE_MATCH / ROLLOUT_EXCLUDED, and repeating a userId always returns the same reason.
```

---

## Prompt 8 — CI, Docker, README

```
Repo: ffaas — completed Spring Boot 3 / Java 21 feature-flag service (CRUD + contextual evaluation + rollout + 2-layer cache; unit tests only, H2 for repo tests, no Docker needed at test time). PRD.md and ARCHITECTURE.md exist at repo root.

Task: finish CI, containerization, and docs.

1. `.github/workflows/ci.yml`: on push to main and on pull_request → checkout, setup-java (Temurin 21, cache: maven), `mvn -B verify`. Fail the build on test failure. Add a status badge to the README.
2. Multi-stage `Dockerfile`: build stage `maven:3.9-eclipse-temurin-21` running `mvn -B -DskipTests package`; runtime stage `eclipse-temurin:21-jre-alpine` (or jammy), non-root user, copies the fat jar, EXPOSE 8080, HEALTHCHECK hitting /actuator/health, env-var driven DB config (DB_URL, DB_USER, DB_PASSWORD).
3. Extend `docker-compose.yml`: add the `app` service built from the Dockerfile, depends_on postgres (condition: service_healthy), wired via env vars — `docker compose up --build` brings up the full stack.
4. `README.md`: one-paragraph overview; badge; quickstart (`docker compose up --build`, or local: compose up postgres + `mvn spring-boot:run`); curl walkthrough — create the PRD §3.1 sample flag, evaluate premium vs free contexts, rollout example, update → observe cache invalidation, delete; link to PRD.md and ARCHITECTURE.md for the contract and design; short "Deploying to DigitalOcean" section: App Platform from Dockerfile + Managed PostgreSQL, set DB_* env vars from the managed DB connection string, health check path /actuator/health; "Known limitations" bullets (single-instance cache, no auth, H2-tested repositories) referencing ARCHITECTURE §8.
5. Add `.dockerignore` (target, .git, .idea) and verify `.gitignore` covers standard Maven/IDE artifacts.

Acceptance criteria:
- `mvn -B verify` passes.
- `docker compose up --build` → app healthy; the full README curl walkthrough works end-to-end against the containerized stack.
- CI workflow YAML is valid (dry parse) and runs green on push.
```