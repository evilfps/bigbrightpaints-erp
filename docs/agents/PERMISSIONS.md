# Agent Permissions Model

Last reviewed: 2026-02-16
Owner: Security & Governance Agent

## Permission Tiers

| Tier | Capabilities | Typical holders |
| --- | --- | --- |
| ReadOnly | Read repo/docs/logs/artifacts | Cartographer, Explorer, Security audit pass |
| RepoWrite | Modify code/docs/tests according to profile scope | Domain agents, GC, Orchestrator |
| CIExec | Execute harness and CI-style checks | QA, Orchestrator, Release |
| StagingDeploy | Trigger staging deploys and rehearsals | Migration, Release |
| ProdDeploy | Trigger production deploy/migration actions | Release with explicit checkpoint |

## Enterprise Runtime Profiles

| Profile | Read scope | Write behavior | Deploy behavior |
| --- | --- | --- | --- |
| `read_only_recon` | full repository | docs/maps only | none |
| `enterprise_autonomous` | full repository | scoped writes; cross-module allowed only by contract-first workflow | none |
| `enterprise_migration` | full repository | migration + runbooks + evidence docs | staging allowed |
| `enterprise_release` | full repository | release/runbook/workflow files | staging allowed, prod checkpointed |
| `break_glass_full` | full repository | unrestricted (incident only) | checkpoint required |

## Operational Defaults (Near Deployment)
- Default profile is `enterprise_autonomous`.
- Default timeout is unlimited.
- Full-codebase exploration is allowed for diagnosis and planning.
- High-risk writes are gated by mechanical checks, not manual policing.
- Agent write boundaries are enforced from `agents/*.agent.yaml` `scope_paths` during orchestrator verify/merge.

## Escalation Policy (Orchestrator-First, Human for Irreversible Prod)
- Orchestrator may approve and continue R2 decisions for code/tests/docs/staging when proof evidence is attached.
- Human approval is mandatory only for irreversible production actions:
  - production migration execution
  - destructive production data operations
  - production permission expansions
  - production deploy go/no-go

R1/R2 are orchestrator-owned when evidence is complete.

## Task-Bound Special Permissions
- Orchestrator may grant temporary task-bound permission expansions to a worker when a slice is blocked and the unblock path is explicit.
- Expansions must be:
  - scoped to named paths/modules
  - tied to a named ticket and slice id
  - justified in ticket timeline/reports with evidence
  - actively monitored during execution
  - time/goal bounded and removed immediately after unblock objective
  - revoked after the unblock objective is complete
- Minor temporary drift for context gathering is acceptable, but implementation and commits must return to packet-defined scope unless orchestrator records an explicit scope exception.

## Evidence Requirements (Proof-First)
- High-risk changes must include `docs/approvals/R2-CHECKPOINT.md`.
- Every non-trivial decision must cite proof artifacts: failing/passing tests, guard outputs, or trace/log evidence.
- Migration changes must update `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md`.
- New cross-module dependency edges require ADR evidence:
  - why needed
  - alternatives rejected
  - boundary preserved

## Secret Handling Rules
- Never commit secrets, private keys, tokens, or credentials.
- Use placeholders in docs/examples.
- CI/deploy credentials must come from secret stores.
- Secret scanning fail policy remains partially unspecified.
  - TODO: define scanner and fail threshold in CI.

## Audit Logging Expectations
Every privileged action should record:
- actor (human or agent id)
- scope (files/services/environment)
- intent (why)
- result (success/failure)
- correlation/trace id

Retention backend and retention period are unspecified.
TODO: define in ops governance docs.

## Mechanical Enforcement
- `ci/check-enterprise-policy.sh`
- `ci/check-architecture.sh`
- `ci/check-orchestrator-layer.sh`
- `ci/architecture/check-allowlist-change-evidence.sh`
