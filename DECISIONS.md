# Decisions — ffaas

Locked during architecture grilling (2026-07-18). Do not relitigate during implementation; propose changes here first.

| Area | Decision |
|---|---|
| Stack | Java 21, Spring Boot 3.x, Maven |
| Storage | PostgreSQL (docker-compose local); Spring Data JPA; Flyway. Conditions as JSONB on the rule row |
| Rule engine | Priority-ordered rules; conditions AND within a rule; rules OR, **first match wins**; no match → `defaultState`. Missing attribute ⇒ condition false, never an error |
| Operators | EQ, NEQ, IN, NOT_IN, GT, LT |
| Rollout | **Extension, additive-only.** `rolloutPercentage` nullable; null ⇒ 100%. Bucket = `abs(murmur3_32(flagKey+":"+userId)) % 100`; excluded ⇒ `ROLLOUT_EXCLUDED` + `defaultState`, no fall-through |
| Evaluate API | `POST /api/v1/flags/{key}/evaluate`; body `{userId required, attributes{scalars}}`; response `{flagKey, enabled, reason, matchedRuleId}` |
| Cache | Two Caffeine layers: L1 flag definitions (60s TTL, evict-on-write) + L2 evaluation results keyed by flagKey+typed-context-hash (30s TTL, per-flag eviction). DB-down evaluate ⇒ serve stale L1 if present, else 503. Results never shared across contexts |
| Auth | None (out of scope). No user-context storage APIs |
| Tests | Unit tests only (JUnit 5 + Mockito); repositories on H2. No Testcontainers |
| CI | GitHub Actions `mvn -B verify` on push/PR |
| Deploy | Multi-stage Dockerfile + compose; DigitalOcean App Platform notes in README |