# Feature Flag as a Service (ffaas) — Architecture & Deliverables Plan

## Context

The user is building a time-boxed, production-grade Feature-Flag-as-a-Service REST API for their `digitalocean-ffaas` GitHub repo. Claude Code is used **only for planning/architecture** — the actual coding happens in a separate workspace via another coding agent. Our job here: produce three deliverable documents in `/Users/s-coding-interview/ffaas` (currently empty) that the user will manually copy over:

1. **PRD.md** — requirements + full API contract
2. **ARCHITECTURE.md** — layers, request lifecycle, caching, DB schema (doubles as repo documentation)
3. **PROMPTS.md** — 6–8 ordered, self-contained copy-paste prompts for the executing coding agent, each with acceptance criteria



## Locked Decisions (from grilling)


| Area         | Decision                                                                                                                                                                                                                                                                                                                            |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stack        | Java 21, Spring Boot 3.x, Maven                                                                                                                                                                                                                                                                                                     |
| Storage      | PostgreSQL via Docker (docker-compose for local); Spring Data JPA; Flyway migrations                                                                                                                                                                                                                                                |
| Rule engine  | Rules ordered by priority; each rule = list of conditions (attribute, operator: EQ/NEQ/IN/NOT_IN/GT/LT, value) AND-ed together; rules OR-ed (first match wins); no match → default state                                                                                                                                            |
| Rollout      | **Extension feature, not core.** Per-rule `rolloutPercentage` is optional; when absent/null the rule applies to 100% of matching users — the regular create-flag → evaluate flow works fully without it. When set, deterministic bucket = `hash(flagKey + ":" + userId) % 100` (MurmurHash3 or SHA-256 based)                       |
| Evaluate API | `POST /api/v1/flags/{key}/evaluate`, body `{userId (required), attributes: {...}}`; response `{flagKey, enabled, reason, matchedRuleId}` with reason ∈ RULE_MATCH, ROLLOUT_EXCLUDED, DEFAULT, FLAG_DISABLED                                                                                                                         |
| Cache        | **Two layers**, both Caffeine: (L1) flag-definition cache keyed by flagKey, write-through eviction on CRUD + 60s TTL safety net; (L2) evaluation-result cache keyed by `(flagKey, normalized-context-hash)`, short TTL (~30s), evicted on flag write. Graceful fallback: on DB failure serve stale definition from cache if present |
| CRUD         | Full: create, get, list, update, delete flags (+ rules embedded in flag payload); no separate user-context APIs — context arrives only in evaluate calls                                                                                                                                                                            |
| Auth         | None (out of scope)                                                                                                                                                                                                                                                                                                                 |
| Tests        | Unit tests only: JUnit 5 + Mockito for engine/service/cache/validation; repository tests on H2                                                                                                                                                                                                                                      |
| CI           | GitHub Actions: `mvn verify` on push/PR (build + tests)                                                                                                                                                                                                                                                                             |
| Deploy       | Dockerfile (multi-stage) + docker-compose (app + postgres) + short DigitalOcean App Platform deploy notes                                                                                                                                                                                                                           |




## Architecture Summary (content that goes into ARCHITECTURE.md)

**Layering** (package `com.ffaas`):

- `api/` — controllers, request/response DTOs, `@RestControllerAdvice` global error handler (RFC-7807-style error body)
- `service/` — `FlagService` (CRUD + cache orchestration), `EvaluationService`
- `engine/` — pure, stateless `RuleEvaluator` + `RolloutBucketer` (no Spring deps → trivially unit-testable)
- `cache/` — `FlagCache` (L1) and `EvaluationResultCache` (L2) wrapping Caffeine
- `repository/` — Spring Data JPA interfaces
- `domain/` — entities: `FeatureFlag` (key unique, name, description, enabled/defaultState, timestamps), `Rule` (priority, rolloutPercentage, conditions as JSONB), stored 1-N
- `config/` — cache config, Jackson config

**Request lifecycle (evaluate)**: validation (Bean Validation) → L2 result-cache check → L1 definition cache (miss → DB load + populate) → flag globally disabled? → FLAG_DISABLED → rules by priority, first full AND match → rollout bucket check → reason + result → store in L2 → respond.

**Write path**: validate → persist (tx) → evict L1 entry + evict L2 entries for that flagKey.

**Validation rules**: flag key regex `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$`; operator/value type compatibility (IN requires array, GT/LT numeric); rolloutPercentage 0–100; priority unique per flag; evaluate requires non-blank userId.

**Error handling**: 400 validation (field-level details), 404 unknown flag, 409 duplicate key, 500 with safe message; DB-down on evaluate → stale-cache fallback, else 503.

## PROMPTS.md — the 8 ordered prompts

Each prompt is self-contained (restates needed context), states files to create, and ends with acceptance criteria the agent must verify (compile + tests pass).

1. **Scaffold** — Spring Boot 3 / Java 21 Maven project, deps (web, data-jpa, validation, postgres, flyway, caffeine, h2 test-scope), docker-compose with Postgres, application.yml profiles, health endpoint
2. **Domain + persistence** — entities, Flyway V1 migration (flags + rules tables, JSONB conditions), repositories, H2-based repo tests
3. **Flag CRUD API** — DTOs, validation annotations + custom validators, controller, service, global error handler, unit tests
4. **Rule engine (core)** — `RuleEvaluator` (operators, AND/OR semantics, first-match-wins, default fallback; no rollout yet — a matching rule applies to all users), exhaustive unit tests (the meatiest test suite)
5. **Evaluation endpoint** — `EvaluationService` + controller wiring engine to storage, reason codes, unit tests
6. **Caching layer** — L1 + L2 Caffeine caches, write-through eviction, TTLs, stale-fallback on DB failure, unit tests proving eviction/fallback behavior
7. **Extension: percentage rollout** — add optional `rolloutPercentage` to rules (null → 100%), `RolloutBucketer` deterministic hash, ROLLOUT_EXCLUDED reason, validation (0–100), unit tests; purely additive — existing flags/tests unaffected
8. **CI + Docker + docs** — GitHub Actions workflow (`mvn verify`), multi-stage Dockerfile, README (run instructions, API examples via curl, DO deploy notes)



## Execution Steps (what I'll do after approval)

1. Write `PRD.md` in `/Users/s-coding-interview/ffaas` — functional requirements, non-goals, full endpoint contract with JSON examples, validation rules, error model
2. Write `ARCHITECTURE.md` — the design above, expanded: component diagram (mermaid), request lifecycle, schema DDL sketch, cache invalidation matrix, trade-offs section (why Caffeine, why first-match-wins, why per-rule rollout)
3. Write `PROMPTS.md` — the 8 prompts, fully written out, copy-paste ready (rollout clearly marked as the extension prompt; PRD likewise lists rollout under "Extension features", core evaluation flow independent of it)



## Verification

- Docs cross-check: every endpoint in PRD appears in ARCHITECTURE lifecycle and in exactly one prompt; every prompt's acceptance criteria are runnable commands (`mvn test`, `docker compose up`, curl examples)
- Prompt self-containment check: each prompt readable standalone without this conversation
- User copies files to their real workspace and feeds prompts sequentially to the executing agent



## Risk note (flagged to user)

Two-layer cache (their choice) is the one deviation from "keep it simple" — mitigated by keeping L2 a thin TTL map with a per-flag eviction index, ~1 class + tests.