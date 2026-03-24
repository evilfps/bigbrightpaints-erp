# Repository Agent Governance

Last reviewed: 2026-03-07

## Review Guidelines (Required)

- Use `Factory-droid` as the integration base for remediation packet review unless a packet explicitly states a narrower stacked-review base.
- Treat docs-only governance changes as docs-only packets: run `bash ci/lint-knowledgebase.sh`, keep the diff limited to docs/governance files, and skip Codex review/subagent review for that packet.
- Any runtime, config, schema, or test-impacting packet must pass `bash ci/check-codex-review-guidelines.sh` before it is considered review-ready.
- High-risk auth, company, RBAC, HR, accounting, orchestrator, or `db/migration_v2` changes must update `docs/approvals/R2-CHECKPOINT.md` in the same packet with scope-specific evidence.
- Review workers may prepare packet/release-gate evidence and commit docs-only governance fixes, but they must never push, merge, or rewrite history.

## R2 Escalation Checkpoint

- Trigger R2 whenever the packet touches high-risk paths enforced by `ci/check-enterprise-policy.sh`.
- Record the exact scope, approval mode, escalation decision, rollback owner, expiry, and verification evidence in `docs/approvals/R2-CHECKPOINT.md`.
- Use orchestrator approval for compatibility-preserving high-risk remediation packets; escalate to human approval if the packet widens privileges, changes tenant boundaries, or introduces destructive migration risk.
- Do not treat degraded runtime evidence as a waiver for product-correctness proof.

## Governance References

- Security posture: `docs/SECURITY.md`
- Agent permission boundaries: `docs/agents/PERMISSIONS.md`
- Review/governance role catalog: `docs/agents/CATALOG.md`
- Active R2 evidence: `docs/approvals/R2-CHECKPOINT.md`
