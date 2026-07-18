# 08 — CI, Docker, README

**What to build:** Anyone can clone the repo, run `docker compose up --build`, and exercise the full API from the README; every push/PR runs the test suite in GitHub Actions; DigitalOcean notes make the deploy story concrete.

**Blocked by:** 03, 04, 05, 06, 07 (final integration bullet — everything demoable must exist).

**Status:** done

**Note (hotfix 2026-07-18):** `Dockerfile` + `.dockerignore` were pulled forward early (DO App Platform has no Java buildpack). **Verify/extend** them here — do not recreate. Remaining: CI workflow, compose `app` service, README + DO deploy notes.

## Spec (from PROMPTS.md §8)
- `.github/workflows/ci.yml`: push to main + pull_request → checkout, setup-java Temurin 21 with Maven cache, `mvn -B verify`. README badge.
- Multi-stage `Dockerfile` (**already present**): confirm `maven:3.9-eclipse-temurin-21` build (`-DskipTests package`) → JRE runtime, non-root user, EXPOSE 8080, HEALTHCHECK on `/actuator/health`, env-driven DB config — extend if gaps.
- `docker-compose.yml`: add `app` service (build: .), depends_on postgres `service_healthy`, env-wired — `docker compose up --build` = full stack.
- `README.md`: overview + badge; quickstart (compose, or local dev); curl walkthrough (create PRD §3.1 flag → evaluate premium vs free → rollout demo → update shows cache invalidation → delete); links to PRD.md / ARCHITECTURE.md; "Deploying to DigitalOcean" (App Platform from Dockerfile + Managed PostgreSQL, DB_* env vars, health check path); "Known limitations" per ARCHITECTURE §8.
- `.dockerignore` (**already present**): confirm ignores (target, .git, .idea); confirm `.gitignore`.

## Acceptance criteria (as test names)
- [ ] (no new unit tests) full existing suite green via `mvn -B verify`

## Manual verification
- [ ] `docker compose up --build` → app healthy; entire README curl walkthrough passes against the containerized stack
- [ ] CI YAML parses; first push runs green

## Context manifest (verify at implement time — may have drifted)
- docker-compose.yml — extend, don't replace (bullet 01)
- src/main/resources/application.yml — env var names the Dockerfile/compose must match
- PRD.md §3.1 — sample payloads for the walkthrough
- ARCHITECTURE.md §8 — known-limitations source