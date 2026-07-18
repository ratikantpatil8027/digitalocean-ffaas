# 06 — Two-layer cache + graceful fallback

**What to build:** Warm evaluations complete with **zero DB round-trips**; flag writes invalidate both cache layers immediately; if the DB goes down, evaluation of a cached flag still succeeds (stale-serve) while uncached flags return 503.

**Blocked by:** 05 — Evaluation endpoint.

**Status:** done

## Spec (from PROMPTS.md §6, ARCHITECTURE.md §4)
- L1 `FlagCache`: Caffeine `Cache<String, FeatureFlag>`, expireAfterWrite 60s, max 10_000, recordStats; values are immutable detached snapshots. API: get/put/evict.
- L2 `EvaluationResultCache`: expireAfterWrite 30s, max 10_000, recordStats. Key = flagKey + ":" + SHA-256 of normalized context (userId + attributes sorted by name, **typed** rendering — `s:premium` vs `n:34` vs `b:true` so `"34"` ≠ `34`). Per-flagKey key index enabling `evictAllForFlag(key)`; index purged on eviction.
- TTLs/sizes from `application.yml` via `@ConfigurationProperties` (`ffaas.cache.*`) — no hardcoded numbers.
- Evaluate order: L2 → L1 → repository (populate L1) → engine → populate L2.
- Fallback: on `DataAccessException` during load, serve from L1 if present + WARN `db_unavailable_served_from_cache`; else rethrow → 503.
- `FlagService`: after successful update/delete **commit** (TransactionSynchronization afterCommit or post-return), `L1.evict(key)` + `L2.evictAllForFlag(key)`; never on rollback.

## Acceptance criteria (as test names)
- [x] `shouldSkipRepositoryOnL2Hit`
- [x] `shouldSkipRepositoryOnL1HitAndPopulateL2`
- [x] `shouldHitRepositoryOnceThenServeFromCaches`
- [x] `shouldIsolateResultsBetweenDifferentContexts`
- [x] `shouldHashNumericAndStringAttributeValuesDifferently`
- [x] `shouldEvictBothLayersOnFlagUpdate`
- [x] `shouldReturn404AfterDeleteDespiteWarmCaches`
- [x] `shouldServeStaleFromL1WhenDbDown`
- [x] `shouldPropagate503WhenDbDownAndCacheCold`
- [x] `shouldExpireL1EntriesAfterTtl` (fake Ticker)
- [x] `shouldPurgeKeyIndexOnEvictAllForFlag`

## Manual verification
- [ ] create flag → evaluate ×2 → `docker compose stop postgres` → same context still 200; uncached flag → 503; `start postgres` recovers

## Context manifest (verify at implement time — may have drifted)
- src/main/java/com/ffaas/service/EvaluationService.java — insertion point for L2/L1 (bullet 05)
- src/main/java/com/ffaas/service/FlagService.java — eviction hooks on update/delete (bullet 03)
- src/main/java/com/ffaas/config/ — @ConfigurationProperties home
- ARCHITECTURE.md §4 — invalidation matrix + correctness invariants (authoritative)
