# Tracker — Feature Flag as a Service (ffaas)
PRD: ./PRD.md | Decisions: ./DECISIONS.md | Design: ./ARCHITECTURE.md | Prompts (reference): ./PROMPTS.md

| NN | Bullet | Blocked by | Status | Tests | Commit |
|----|--------|-----------|--------|-------|--------|
| 01 | Project scaffold | — | done | 1/1 green (`contextLoads`); QA clean | b7f9342 |
| 02 | Domain + persistence | 01 | ready | – | – |
| 03 | Flag CRUD API | 02 | blocked | – | – |
| 04 | Rule engine (core) | 02 | blocked | – | – |
| 05 | Evaluation endpoint | 03, 04 | blocked | – | – |
| 06 | Two-layer cache + fallback | 05 | blocked | – | – |
| 07 | Percentage rollout (extension) | 06 | blocked | – | – |
| 08 | CI, Docker, README | 03–07 | blocked | – | – |

## Journey to destination
[x] 01 scaffold → [ ] 02 persistence → [ ] 03 CRUD API → [ ] 04 rule engine → [ ] 05 evaluate endpoint → [ ] 06 cache → [ ] 07 rollout → [ ] 08 CI/Docker/docs → 🏁 REST service that stores flags and dynamically evaluates them against user context, with zero-DB-hit warm reads

## Log
### 2026-07-18 — 01 Project scaffold
- Status: done
- Tests: `mvn -B verify` — Tests run: 1, Failures: 0, Errors: 0 (`contextLoads` green under `test` profile / H2)
- Notes: Spring Boot 3.4.5 / Java 21 Maven skeleton; packages api/service/engine/cache/repository/domain/config; docker-compose Postgres 16; Jackson FAIL_ON_UNKNOWN_PROPERTIES + ISO-8601; no business endpoints. Manual `docker compose` + health check not run (Docker not installed in this environment).

### 2026-07-18 — 01 QA gate
- Reviews: Bugbot — 0 findings; Jackson unknown-property rejection spot-checked green
- Suite: `mvn -B verify` BUILD SUCCESS (1/1)
- Fixes: QA-01 TRACKER commit SHA corrected to `b7f9342`; no source fixes required
- Result: clean — bullet 01 remains done; frontier still 02
