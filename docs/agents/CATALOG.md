# Agent Review Catalog

Last reviewed: 2026-03-07

## Governance Agents

| Agent / role | Primary responsibility | Required evidence before handoff |
| --- | --- | --- |
| Orchestrator | Own final review ordering, R2 approval mode, and merge readiness decisions | Packet scope confirmation, reviewer assignment, release-gate decision |
| Packet-governance worker | Prepare packet templates, release-gate notes, review-policy fixes, and lineage evidence | Updated governance docs, validator output, rollback notes, packet links |
| Security/backend implementation worker | Deliver in-scope code/test changes for auth, company, and adjacent high-risk areas | Targeted tests, gate-fast evidence when required, contract/handoff parity |
| Review-only validator | Re-run validators and inspect finished packet diffs for scope or governance drift | Validator transcript, findings synthesis, explicit pass/fail recommendation |

## Required Governance Surfaces

- `AGENTS.md`
- `docs/SECURITY.md`
- `docs/agents/PERMISSIONS.md`
- `docs/approvals/R2-CHECKPOINT.md`

These files form the minimum repo-root review-policy surface used by the published remediation packets.
