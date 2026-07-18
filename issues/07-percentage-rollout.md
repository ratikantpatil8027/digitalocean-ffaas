# 07 — Percentage rollout (extension)

**What to build:** A rule may carry `rolloutPercentage`; matching users are then deterministically included or excluded by a hash of (flagKey, userId). **Purely additive:** flags without the field behave exactly as before — no existing test assertion may change.

**Blocked by:** 06 — Two-layer cache.

**Status:** blocked

## Spec (from PROMPTS.md §7, PRD.md §4.3)
- `RolloutBucketer` (pure, in engine): `bucket(flagKey, userId) = abs(murmur3_32(flagKey + ":" + userId)) % 100` — Guava `Hashing.murmur3_32_fixed()` or documented inline impl; handle `Integer.MIN_VALUE`; stable across JVM restarts.
- `RuleEvaluator`: first matching rule with non-null percentage → included iff `bucket < percentage` → RULE_MATCH as before; excluded → `(defaultState, ROLLOUT_EXCLUDED, rule.id)` and evaluation **stops** (no fall-through to lower-priority rules). Add `ROLLOUT_EXCLUDED` to Reason.
- Null percentage ⇒ code path identical to bullet 04 behavior.
- L2 cache needs no change (flagKey+userId already in the key) — prove with a test.

## Acceptance criteria (as test names)
- [ ] `shouldReturnSameBucketForSameFlagAndUserRepeatedly`
- [ ] `shouldReturnDifferentBucketsAcrossFlagsForSameUser`
- [ ] `shouldAlwaysExcludeAtZeroPercent`
- [ ] `shouldAlwaysIncludeAtHundredPercent`
- [ ] `shouldBehaveIdenticallyToCoreWhenPercentageNull`
- [ ] `shouldIncludeRoughly30PercentOf10000Users` (assert 27–33%)
- [ ] `shouldNotFallThroughToLowerPriorityRuleWhenExcluded`
- [ ] `shouldNotLeakExcludedResultToDifferentUserViaL2Cache`

## Manual verification
- [ ] rule with rolloutPercentage=30: ~10 distinct userIds → mix of RULE_MATCH / ROLLOUT_EXCLUDED; repeats are stable
- [ ] full pre-existing test suite passes UNMODIFIED

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/engine/RuleEvaluator.java + EvaluationOutcome/Reason — extension point (bullet 04)
- src/test/java/com/ffaas/engine/ — existing tests that must stay untouched
- PRD.md §4.3 — rollout semantics (authoritative)
- pom.xml — if adding Guava, pin a current version