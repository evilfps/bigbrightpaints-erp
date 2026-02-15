---
name: safe-refactor
description: Execute small refactors with boundary enforcement and remediation-first feedback.
---

# Skill: safe-refactor

## Boundaries
- Allowed: local consolidations, naming cleanup, duplicated helper removal, dead-code deletion.
- Not allowed: broad semantic rewrites, hidden behavior changes, risky schema mutations.

## Procedure
1. Define explicit acceptance criteria and unchanged behavior expectations.
2. Apply minimal refactor in one slice.
2a. If multi-domain touch is required, use contract-first order:
   contracts/events -> producer -> consumers -> orchestrator.
3. Add/adjust tests if behavior protection is missing.
4. Run boundary and docs checks.
5. Run focused test command for touched modules.
6. If a guard fails, add remediation text and either fix or escalate.

## Required tools/commands
- `bash ci/check-architecture.sh`
- `bash ci/lint-knowledgebase.sh`
- Module-targeted Maven test command

## Outputs
- Minimal refactor patch
- Test evidence
- Any allowlist/rule update with rationale
