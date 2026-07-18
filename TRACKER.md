# Tracker — Feature Flag as a Service (ffaas)
PRD: ./PRD.md | Decisions: ./DECISIONS.md | Design: ./ARCHITECTURE.md | Prompts (reference): ./PROMPTS.md

| NN | Bullet | Blocked by | Status | Tests | Commit |
|----|--------|-----------|--------|-------|--------|
| 01 | Project scaffold | — | done | 1/1 green (`contextLoads`); QA clean | b7f9342 |
| 02 | Domain + persistence | 01 | done | 6/6 green (5 repo + contextLoads) | da0a0d7 |
| 03 | Flag CRUD API | 02 | done | 23/23 green; QA clean | 23efea6 |
| 04 | Rule engine (core) | 02 | done | 13/13 green (`RuleEvaluatorTest`); suite 36/36; QA clean | 096d752 |
| 05 | Evaluation endpoint | 03, 04 | ready | – | – |
| 06 | Two-layer cache + fallback | 05 | blocked | – | – |
| 07 | Percentage rollout (extension) | 06 | blocked | – | – |
| 08 | CI, Docker, README | 03–07 | blocked | – | – |

## Journey to destination
[x] 01 scaffold → [x] 02 persistence → [x] 03 CRUD API → [x] 04 rule engine → [ ] 05 evaluate endpoint → [ ] 06 cache → [ ] 07 rollout → [ ] 08 CI/Docker/docs → 🏁 REST service that stores flags and dynamically evaluates them against user context, with zero-DB-hit warm reads

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

### 2026-07-18 — 02 Domain + persistence
- Status: done
- Tests: `mvn -B verify` — Tests run: 6, Failures: 0, Errors: 0
  - `shouldSaveAndReloadFlagWithRulesOrderedByPriority`
  - `shouldRoundTripConditionsThroughJsonConverter`
  - `shouldRejectDuplicateFlagKey`
  - `shouldCascadeDeleteRulesWhenFlagDeleted`
  - `shouldRejectDuplicatePriorityWithinFlag`
  - `contextLoads`
- Notes: Flyway V1 (quoted `"key"`, `TIMESTAMP WITH TIME ZONE`, `JSON` for H2 dual-run; Postgres-compatible). Entities FeatureFlag/Rule/Condition/Operator + ConditionListConverter; FeatureFlagRepository. Manual Postgres migrate deferred (no Docker).

### 2026-07-18 — 03 Flag CRUD API
- Status: done
- Tests: `mvn -B verify` — Tests run: 21, Failures: 0, Errors: 0
  - `shouldCreateFlagAndReturn201WithLocation`
  - `shouldReturn409WhenKeyAlreadyExists`
  - `shouldReturn400WithFieldPathWhenInOperatorHasScalarValue`
  - `shouldReturn400WhenRulePrioritiesDuplicate`
  - `shouldReturn400WhenBodyKeyDiffersFromPathOnUpdate`
  - `shouldReturn404ForUnknownFlagOnGetUpdateDelete`
  - `shouldListFlagsWithPagingEnvelope`
  - `shouldReplaceRulesAtomicallyOnUpdate`
  - `shouldMapUnexpectedExceptionTo500WithSafeMessage`
  - `shouldMapMalformedJsonTo400`
  - (+ FlagService create/duplicate/race/not-found; prior repo + contextLoads)
- Notes: FlagController + FlagService + GlobalExceptionHandler; DTO records; `@ValidFlagRules` for unique priorities + operator/value compatibility; no cache/eval yet. Manual curl against compose deferred (no Docker).

### 2026-07-18 — 03 QA gate
- Reviews: Bugbot — 2 findings (both fixed)
- Suite: `mvn -B verify` BUILD SUCCESS (23/23 post-fix)
- Fixes:
  - QA-01: flush orphan rule deletes before re-insert so PUT can reuse priorities (`FlagService` + `FlagServicePersistenceTest`)
  - QA-02: reject null `rules`/`conditions` elements with 400 (`@NotNull` + validator + controller test)
- Result: clean — bullet 03 remains done; frontier still 04

### 2026-07-18 — 04 Rule engine (core)
- Status: done
- Tests: `mvn -B verify` — Tests run: 34, Failures: 0, Errors: 0
  - `shouldReturnFlagDisabledWhenGlobalToggleOffEvenIfRulesMatch`
  - `shouldMatchEqWithNumericCoercionAcrossBoxing`
  - `shouldNotMatchEqOnTypeMismatch`
  - `shouldMatchInWhenValuePresentInList`
  - `shouldTreatMissingAttributeAsNonMatchForAllOperators`
  - `shouldReturnFalseForGtWhenContextValueNotNumeric`
  - `shouldFailRuleWhenAnySingleConditionFails`
  - `shouldPickLowestPriorityRuleWhenMultipleMatch`
  - `shouldReturnDefaultStateWhenNoRuleMatches`
  - `shouldReturnDefaultWithEmptyRules`
  - `shouldExposeUserIdAsContextAttribute`
  - (+ prior CRUD/service/repo + contextLoads)
- Notes: Pure `com.ffaas.engine` (`RuleEvaluator`, `EvaluationOutcome`, `Reason`); zero Spring imports; rollout ignored. Manual `grep` for `org.springframework` under engine/ empty.

### 2026-07-18 — Hotfix: Dockerfile pulled forward from 08
- Reason: DigitalOcean App Platform has no Java buildpack — need a multi-stage `Dockerfile` (+ `.dockerignore`) before bullet 08.
- Scope: Placeholder empty files from `a06d45c` filled with multi-stage Maven 21 → JRE alpine image (non-root, HEALTHCHECK `/actuator/health`). Compose `app` service, CI, and README remain bullet 08.
- Bullet 08: **verify/extend** existing `Dockerfile` / `.dockerignore` — do not recreate from scratch.

### 2026-07-18 — 04 QA gate
- Reviews: Bugbot — 2 findings (both fixed)
- Suite: `mvn -B verify` BUILD SUCCESS (36/36 post-fix)
- Fixes:
  - QA-01: malformed `NOT_IN` value (non-Collection) must not match — require Collection before negating membership (`RuleEvaluator` + `shouldNotMatchNotInWhenConditionValueIsNotACollection`)
  - QA-02: null condition value must not satisfy `NEQ`/`EQ` (`RuleEvaluator` + `shouldNotMatchNeqWhenConditionValueIsNull`)
- Result: clean — bullet 04 remains done; frontier still 05
