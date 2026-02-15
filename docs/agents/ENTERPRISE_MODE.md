# Enterprise Autonomous Mode

Last reviewed: 2026-02-15
Owner: Orchestrator Agent

This document defines the near-deployment policy for long-running autonomous flows.

## Goal
- Keep autonomous, no-timeout execution.
- Keep full-codebase exploration.
- Reduce deployment risk using mechanical write controls and escalation evidence.
- Let orchestrator make decisions by default, with proof artifacts instead of assumptions.

## Runtime Profiles

| Profile | Read scope | Write scope | Deploy rights | Use case |
| --- | --- | --- | --- | --- |
| `read_only_recon` | full repo | docs/maps only | none | mapping, triage, risk discovery |
| `enterprise_autonomous` | full repo | in-scope + contract-first cross-module changes | none | default build/fix/refactor loop |
| `enterprise_migration` | full repo | migration + runbooks + evidence docs | staging only | migration prep and rehearsal |
| `enterprise_release` | full repo | release scripts/workflows/runbooks | staging; prod with checkpoint | release readiness and rollout |
| `break_glass_full` | full repo | full repo | prod with checkpoint | incident containment only |

## Required Controls (Enterprise)
1. Contract-first cross-module order: contracts -> producer -> consumers -> orchestrator.
2. High-risk path changes require R2 checkpoint evidence in `docs/approvals/R2-CHECKPOINT.md`.
3. Migration changes require runbook updates in same change set.
4. Architecture allowlist changes require ADR evidence.
5. High-risk logic changes require tests, or explicit R2 test waiver.
6. Every risky decision must include proof: command outputs, tests/guards, or trace/log evidence.

## Decision Rights
- Orchestrator owns autonomous R1/R2 decisions for repo and staging work when proof pack is complete.
- Human approvals are reserved for irreversible production operations (R3).

## Mechanical Enforcement
- `ci/check-enterprise-policy.sh`
- `ci/check-architecture.sh`
- `ci/architecture/check-allowlist-change-evidence.sh`
- `.github/workflows/ci.yml` enterprise-policy-check job

## Deployment Readiness Objective
Near-deployment readiness means:
- all policy guards green,
- unresolved risks explicitly listed,
- rollback and migration runbooks current,
- R2/R3 checkpoints present where required.
