# 05 — Evaluation endpoint

**What to build:** A client can POST a user context to `/api/v1/flags/{key}/evaluate` and get back the contextual ON/OFF decision with an explanatory reason code — the product's core feature, end to end (repository-direct; caching arrives in bullet 06).

**Blocked by:** 03 — Flag CRUD API, 04 — Rule engine (core).

**Status:** done

## Spec (from PROMPTS.md §5, PRD.md §3.6)
- `EvaluateRequest(userId @NotBlank ≤128, attributes optional Map<String,Object> default empty)`; custom validation: attribute values scalar only (String/Number/Boolean; reject null/nested/list), ≤ 50 entries, field paths like `attributes.someKey`.
- `EvaluateResponse(flagKey, enabled, reason, matchedRuleId)`.
- `EvaluationService`: repository `findByKey` (miss → FlagNotFoundException) → `RuleEvaluator` → response. One DEBUG log line per evaluation (flagKey, userId, reason, matchedRuleId).

## Acceptance criteria (as test names)
- [x] `shouldReturnRuleMatchWithMatchedRuleId`
- [x] `shouldReturnDefaultWhenNoRuleMatches`
- [x] `shouldReturnFlagDisabledWhenToggleOff`
- [x] `shouldReturn404ForUnknownFlag`
- [x] `shouldReturn400WhenUserIdMissing`
- [x] `shouldReturn400WhenAttributeValueIsList`
- [x] `shouldDefaultAttributesToEmptyMapWhenAbsent`

## Manual verification
- [ ] PRD §3.1 flag: premium/us-east context → RULE_MATCH; free tier → DEFAULT; PUT enabled=false → FLAG_DISABLED

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/engine/RuleEvaluator.java — decision logic (bullet 04)
- src/main/java/com/ffaas/service/FlagService.java + api/GlobalExceptionHandler.java — existing patterns to follow (bullet 03)
- src/main/java/com/ffaas/repository/FeatureFlagRepository.java
- PRD.md §3.6 — request/response contract, reason table