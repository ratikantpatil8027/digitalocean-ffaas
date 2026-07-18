# 04 — Rule engine (core)

**What to build:** A pure, framework-free decision engine: given a flag definition and a user context, produce {enabled, reason, matchedRuleId} per the PRD semantics. No rollout in this bullet — a matching rule applies to every user (`rolloutPercentage` is ignored).

**Blocked by:** 02 — Domain + persistence. (Does NOT need 03 — can run in parallel with the CRUD API.)

**Status:** done

## Spec (from PROMPTS.md §4, PRD.md §4.1–4.2)
- `com.ffaas.engine` — HARD CONSTRAINT: zero Spring/JPA/cache imports.
- `EvaluationOutcome(boolean enabled, Reason reason, UUID matchedRuleId)`; `Reason`: FLAG_DISABLED, RULE_MATCH, DEFAULT (ROLLOUT_EXCLUDED added later in bullet 07).
- `RuleEvaluator.evaluate(flag, userId, attributes)`: effective context = attributes + "userId"→userId (userId wins); disabled flag short-circuits; rules by ascending priority, all-conditions-AND, first match wins; else defaultState/DEFAULT.
- Matching: missing attribute ⇒ false; EQ/NEQ type-aware with numeric coercion (34 == 34.0); IN/NOT_IN membership with same numeric equality; GT/LT numbers only (non-number ⇒ false), compared as double. Never throws on bad data.

## Acceptance criteria (as test names)
- [x] `shouldReturnFlagDisabledWhenGlobalToggleOffEvenIfRulesMatch`
- [x] `shouldMatchEqWithNumericCoercionAcrossBoxing`
- [x] `shouldNotMatchEqOnTypeMismatch`
- [x] `shouldMatchInWhenValuePresentInList`
- [x] `shouldTreatMissingAttributeAsNonMatchForAllOperators`
- [x] `shouldReturnFalseForGtWhenContextValueNotNumeric`
- [x] `shouldFailRuleWhenAnySingleConditionFails`
- [x] `shouldPickLowestPriorityRuleWhenMultipleMatch`
- [x] `shouldReturnDefaultStateWhenNoRuleMatches`
- [x] `shouldReturnDefaultWithEmptyRules`
- [x] `shouldExposeUserIdAsContextAttribute`

## Manual verification
- [x] `grep -r "org.springframework" src/main/java/com/ffaas/engine/` → empty

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/domain/ — FeatureFlag, Rule, Condition, Operator (inputs to the engine)
- PRD.md §4.1–4.2 — authoritative matching semantics
- ARCHITECTURE.md §3 — engine rationale and constraints