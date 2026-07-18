# 03 — Flag CRUD API

**What to build:** A client can create, read, list, update, and delete feature flags over REST with strict validation and a consistent error envelope — the full management surface (no evaluation yet).

**Blocked by:** 02 — Domain + persistence.

**Status:** done

## Spec (from PROMPTS.md §3, PRD.md §3 + §6)
- Endpoints: `POST /api/v1/flags` (201 + Location; 409 on dup), `GET /flags/{key}`, `GET /flags?page&size` (items/page/size/totalItems; default 20, max 100), `PUT /flags/{key}` (full replace; key immutable — body key must match path else 400; rules replaced atomically), `DELETE /flags/{key}` (204).
- DTO records only (never expose entities); error envelope exactly `{status, error, message, details[{field, issue}], timestamp, path}`.
- Validation: key regex `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$`; name ≤100; description ≤500; ≤50 rules; 1–20 conditions/rule; rolloutPercentage null or 0–100; custom class-level validator for unique priorities + operator/value compatibility (EQ/NEQ scalar, IN/NOT_IN non-empty scalar list, GT/LT number) with paths like `rules[0].conditions[1].value`.
- `FlagService` transactional; `FlagNotFoundException`/`DuplicateFlagKeyException` (also from DataIntegrityViolation race).
- `GlobalExceptionHandler` mappings per ARCHITECTURE.md §6 (VALIDATION_FAILED, MALFORMED_JSON, FLAG_NOT_FOUND, DUPLICATE_KEY, SERVICE_UNAVAILABLE, INTERNAL_ERROR).

## Acceptance criteria (as test names)
- [x] `shouldCreateFlagAndReturn201WithLocation`
- [x] `shouldReturn409WhenKeyAlreadyExists`
- [x] `shouldReturn400WithFieldPathWhenInOperatorHasScalarValue`
- [x] `shouldReturn400WhenRulePrioritiesDuplicate`
- [x] `shouldReturn400WhenBodyKeyDiffersFromPathOnUpdate`
- [x] `shouldReturn404ForUnknownFlagOnGetUpdateDelete`
- [x] `shouldListFlagsWithPagingEnvelope`
- [x] `shouldReplaceRulesAtomicallyOnUpdate`
- [x] `shouldMapUnexpectedExceptionTo500WithSafeMessage`
- [x] `shouldMapMalformedJsonTo400`

## Manual verification
- [ ] curl the PRD §3.1 sample flag: create → 201, re-create → 409, invalid IN payload → 400 with field details (Docker unavailable in agent environment)
- [x] `mvn -B verify` green (23/23 after QA fixes)

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/domain/ — FeatureFlag, Rule, Condition, Operator (bullet 02)
- src/main/java/com/ffaas/repository/FeatureFlagRepository.java
- PRD.md §3, §6 — contract + error model (authoritative)
- ARCHITECTURE.md §6 — handler mapping table

## QA gate (2026-07-18)

**Reviews:** Bugbot — 2 findings (see fix tasks). Spec/compliance against Prompt 3 / this issue.

**Suite (pre-fix):** `mvn -B verify` — Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS.

**Suite (post-fix):** `mvn -B verify` — Tests run: 23, Failures: 0, Errors: 0, BUILD SUCCESS.

### Fix tasks
- [x] QA-01: PUT reusing `(flag_id, priority)` fails — Hibernate inserts new rules before orphan deletes hit unique constraint → `entityManager.flush()` after `rules.clear()`; regression `shouldUpdateFlagReusingSameRulePriorities`
- [x] QA-02: Null elements in `rules` (and conditions) skipped by `FlagRulesValidator` → 500 on toEntity; reject with 400 via `@NotNull` on list elements + validator; test `shouldReturn400WhenRuleElementIsNull`
