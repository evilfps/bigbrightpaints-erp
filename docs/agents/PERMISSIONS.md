# Agent Permissions

Last reviewed: 2026-03-07

## Role Boundaries

| Role | May do | Must not do |
| --- | --- | --- |
| Orchestrator | Approve packet readiness, coordinate stacked review order, decide whether human escalation is needed | Bypass required packet evidence or approve widened scope without updating governance artifacts |
| Packet-governance worker | Edit docs/governance artifacts, assemble packet/release-gate evidence, run policy validators | Push, merge, rewrite history, or widen into unrelated product-code work |
| Implementation worker | Change in-scope code/tests, refresh packet evidence tied to the feature, run required validators | Skip packet controls for high-risk paths or mix lanes in one packet |
| Review-only validator/reviewer | Audit diffs, run review checks, report findings | Commit product-code changes or act as merge approver |

## Required Approval Rules

- Review-only agents do not merge or push changes.
- Docs-only governance packets may skip Codex review/subagent review after `bash ci/lint-knowledgebase.sh` passes.
- High-risk packets must update `docs/approvals/R2-CHECKPOINT.md` and preserve rollback ownership before review is considered complete.
