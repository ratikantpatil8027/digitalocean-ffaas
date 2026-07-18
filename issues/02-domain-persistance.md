# 02 — Domain model + persistence

**What to build:** Feature flags with ordered rules and JSON conditions can be saved and reloaded from the database as one aggregate — the storage foundation for CRUD and evaluation.

**Blocked by:** 01 — Project scaffold.

**Status:** blocked

## Spec (from PROMPTS.md §2, ARCHITECTURE.md §5)
- Flyway `V1__init.sql`: `feature_flags` (id UUID PK, key varchar(64) unique, name varchar(100), description varchar(500), enabled, default_state, created_at/updated_at timestamptz) + `flag_rules` (id UUID PK, flag_id FK cascade, priority ≥ 0, serve, rollout_percentage null check 0–100, conditions JSONB, unique(flag_id, priority)); index on flag_id. Postgres-first; H2 JSON via PostgreSQL mode acceptable in tests.
- Entities: `FeatureFlag` (@OneToMany cascade ALL, orphanRemoval, eager, @OrderBy("priority ASC"); @PrePersist/@PreUpdate timestamps), `Rule` (conditions column ↔ `List<Condition>` via Jackson `AttributeConverter`), `Condition` value class (attribute, `Operator` enum EQ/NEQ/IN/NOT_IN/GT/LT, value Object).
- `FeatureFlagRepository`: `findByKey`, `existsByKey`, `deleteByKey`.

## Acceptance criteria (as test names)
- [ ] `shouldSaveAndReloadFlagWithRulesOrderedByPriority`
- [ ] `shouldRoundTripConditionsThroughJsonConverter`
- [ ] `shouldRejectDuplicateFlagKey`
- [ ] `shouldCascadeDeleteRulesWhenFlagDeleted`
- [ ] `shouldRejectDuplicatePriorityWithinFlag`

## Manual verification
- [ ] Migration applies cleanly against real Postgres (`docker compose up -d` + app start)

## Context manifest (verify at implement time — may have drifted)
- src/main/resources/application*.yml — Flyway/H2 config from bullet 01
- src/main/java/com/ffaas/domain/ — target package (empty)
- ARCHITECTURE.md §5 — exact DDL
- PRD.md §2 — field bounds and semantics