# 03 — Flag CRUD API

**What to build:** A client can create, read, list, update, and delete feature flags over REST with strict validation and a consistent error envelope — the full management surface (no evaluation yet).

**Blocked by:** 02 — Domain + persistence.

**Status:** blocked

## Spec (from PROMPTS.md §3, PRD.md §3 + §6)
- Endpoints: `POST /api/v1/flags` (201 + Location; 409 on dup), `GET /flags/{key}`, `GET /flags?page&size` (items/page/size/totalItems; default 20, max 100), `PUT /flags/{key}` (full replace; key immutable — body key must match path else 400; rules replaced atomically), `DELETE /flags/{key}` (204).
- DTO records only (never expose entities); error envelope exactly `{status, error, message, details[{field, issue}], timestamp, path}`.
- Validation: key regex `^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$`; name ≤100; description ≤500; ≤50 rules; 1–20 conditions/rule; rolloutPercentage null or 0–100; custom class-level validator for unique priorities + operator/value compatibility (EQ/NEQ scalar, IN/NOT_IN non-empty scalar list, GT/LT number) with paths like `rules[0].conditions[1].value`.
- `FlagService` transactional; `FlagNotFoundException`/`DuplicateFlagKeyException` (also from DataIntegrityViolation race).
- `GlobalExceptionHandler` mappings per ARCHITECTURE.md §6 (VALIDATION_FAILED, MALFORMED_JSON, FLAG_NOT_FOUND, DUPLICATE_KEY, SERVICE_UNAVAILABLE, INTERNAL_ERROR).

## Acceptance criteria (as test names)
- [ ] `shouldCreateFlagAndReturn201WithLocation`
- [ ] `shouldReturn409WhenKeyAlreadyExists`
- [ ] `shouldReturn400WithFieldPathWhenInOperatorHasScalarValue`
- [ ] `shouldReturn400WhenRulePrioritiesDuplicate`
- [ ] `shouldReturn400WhenBodyKeyDiffersFromPathOnUpdate`
- [ ] `shouldReturn404ForUnknownFlagOnGetUpdateDelete`
- [ ] `shouldListFlagsWithPagingEnvelope`
- [ ] `shouldReplaceRulesAtomicallyOnUpdate`
- [ ] `shouldMapUnexpectedExceptionTo500WithSafeMessage`
- [ ] `shouldMapMalformedJsonTo400`

## Manual verification
- [ ] curl the PRD §3.1 sample flag: create → 201, re-create → 409, invalid IN payload → 400 with field details

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/domain/ — FeatureFlag, Rule, Condition, Operator (bullet 02)
- src/main/java/com/ffaas/repository/FeatureFlagRepository.java
- PRD.md §3, §6 — contract + error model (authoritative)
- ARCHITECTURE.md §6 — handler mapping table