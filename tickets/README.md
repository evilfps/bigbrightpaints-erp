# Local Ticket Ledger

This directory is a local, git-tracked operations ledger for harness-engineering execution.

## Purpose
- Replace ad-hoc status notes with machine-readable ticket artifacts.
- Track per-slice worktree assignments, reviewer evidence, and merge readiness.
- Keep execution legible for orchestrator + human oversight.

## Ticket Layout
For each ticket id (`TKT-*`), the orchestrator writes:

- `ticket.yaml`: source of truth for slices, lanes, branch/worktree mapping, and status.
- `SUMMARY.md`: compact board view.
- `TIMELINE.md`: append-only activity log.
- `commands/tmux-launch.sh`: tmux send-keys block for lane dispatch.
- `slices/<slice-id>/TASK_PACKET.md`: copy/paste instructions for the assigned agent.
  - Packet is invariant-first: feature objective, current failure, expected behavior, constraints, assumptions, and evidence contract.
- `slices/<slice-id>/reviews/<reviewer>.md`: review evidence files.
- `slices/<slice-id>/orchestrator-review.md`: orchestrator senior pre-merge review result.
- `reports/verify-*.md`: verification + merge reports.

## Core Commands
Create + plan + worktrees:
- `python3 scripts/harness_orchestrator.py bootstrap --title "<title>" --goal "<goal>" --paths "path1,path2"`

Regenerate tmux launch block:
- `python3 scripts/harness_orchestrator.py dispatch --ticket-id <TKT-ID>`

Record review result:
- `python3 scripts/harness_orchestrator.py review --ticket-id <TKT-ID> --slice-id SLICE-01 --reviewer qa-reliability --status approved --findings "none" --commands "bash scripts/verify_local.sh" --artifacts "artifacts/gate-fast/..."`

Verify all slices:
- `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID>`

Verify and merge ready slices:
- `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID> --merge`

Verify + merge + remove merged slice worktrees:
- `python3 scripts/harness_orchestrator.py verify --ticket-id <TKT-ID> --merge --cleanup-worktrees`

## Agent Ticket Claim Guide
Before writing code, each implementation agent must claim a slice.

Required claim sequence:
1. Identify yourself in first line: `I am <agent-id> and I own <slice-id>.`
2. Mark `ticket.yaml` slice status `ready -> taken`.
3. Append claim event in `TIMELINE.md` with agent id, slice id, branch, worktree, UTC timestamp.
4. Move status `taken -> in_progress` only after claim is logged.
5. On completion/block, move to `in_review` or `blocked` and include evidence.

Rules:
- No dual claims on same slice.
- No cross-slice implementation unless orchestrator records explicit scope exception.
- Reviewer agents are review-only; they do not implement.
- Unclaimed submissions are rejected.

## What To Clone and Where To Work
Canonical integration branch is `harness-engineering-orchestrator` unless ticket overrides.

Standard setup:
1. `git clone git@github.com:anasibnanwar-XYE/bigbrightpaints-erp.git`
2. `cd bigbrightpaints-erp`
3. `git fetch origin`
4. `git checkout harness-engineering-orchestrator`
5. `git pull --ff-only origin harness-engineering-orchestrator`
6. `git worktree add ../orchestrator_erp_worktrees/<TKT-ID>/<agent-id> -b tickets/<tkt-id>/<agent-id> harness-engineering-orchestrator`

Isolation rules:
- One worktree per agent.
- One slice per branch.
- Do not reuse stale worktrees after base branch moves forward.

## PR Hygiene and Cleanup
- Use PR title format: `<TKT-ID> <SLICE-ID>: <short description>`.
- Include ticket id + slice id in PR body with scope paths and checks run.
- Merge only when required checks are green on latest head commit.
- After merge confirmation, delete only orchestrator-created slice branches.
- If branch is stale/diverged, rebase or rebuild from canonical base before rerun.

## Guardrails
- Primary module agents are blocked from merging if branch changes exceed their `scope_paths` permission boundaries.
- Reviewer evidence is mandatory before merge.
- Cross-slice overlaps across different implementation agents are blocked as `coordination_required` until orchestrator replans/consolidates.
- Merged slice worktrees are deleted by default if `automation.cleanup_worktrees_on_merge: true` in `agents/orchestrator-layer.yaml`.
- Human approval is reserved for R3 irreversible production actions only.
