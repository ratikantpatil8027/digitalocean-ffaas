# 01 — Project scaffold

**What to build:** A bootable Spring Boot 3 / Java 21 Maven service with Postgres wired via docker-compose, Flyway enabled, an H2 test profile, and a passing health check — the walking skeleton every later bullet builds on.

**Blocked by:** None — can start immediately.

**Status:** done

## Spec (from PROMPTS.md §1)
- Maven project `com.ffaas:ffaas`, Java 21. Deps: starter-web, starter-data-jpa, starter-validation, starter-actuator, postgresql (runtime), flyway-core + flyway-database-postgresql, caffeine, starter-test, h2 (test).
- Packages: `com.ffaas.{api,service,engine,cache,repository,domain,config}`.
- `application.yml`: datasource from env vars `DB_URL`/`DB_USER`/`DB_PASSWORD` with local defaults (`jdbc:postgresql://localhost:5432/ffaas`, `ffaas`/`ffaas`); Flyway on; `ddl-auto: validate`; actuator health only. `application-test.yml`: H2 PostgreSQL mode (`jdbc:h2:mem:ffaas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`), Flyway on.
- `docker-compose.yml`: `postgres:16-alpine`, db/user/pass `ffaas`, port 5432, healthcheck, named volume.
- Jackson: `FAIL_ON_UNKNOWN_PROPERTIES=true`, ISO-8601 dates.

## Acceptance criteria (as test names)
- [x] `contextLoads` (test profile, H2, no Docker required)

## Manual verification
- [x] `mvn -B verify` green
- [ ] `docker compose up -d` + `mvn spring-boot:run` → `GET /actuator/health` returns `{"status":"UP"}` (Docker unavailable in agent environment)

## Context manifest (verify at implement time — may have drifted)
- PRD.md — product contract (read §1, §7)
- ARCHITECTURE.md — package layout §1, config expectations
- Greenfield: no source files exist yet

## QA gate (2026-07-18)

**Reviews:** Bugbot — no findings. Spec/compliance pass against Prompt 1 / this issue.

**Suite:** `mvn -B verify` — Tests run: 1, Failures: 0, Errors: 0, BUILD SUCCESS.

**Extra checks (not committed):** Jackson `FAIL_ON_UNKNOWN_PROPERTIES` verified via transient ObjectMapper test (green); Docker still unavailable — manual health check remains deferred.

### Fix tasks
- [x] QA-01: Correct TRACKER.md Commit column for bullet 01 (`6f4851d` → `b7f9342`) — stale after amend loop
- [x] QA-02: No code defects in scaffold scope — nothing to change in `src/` / `pom.xml` / compose