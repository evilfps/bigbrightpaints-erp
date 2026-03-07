# Packet Governance

Packet-branch, base-branch, review, and release-gate rules for the current remediation mission.

**What belongs here:** base branch rules, packet template expectations, review/merge handoff rules, and cross-packet sequencing notes.

---

## Base Branch

- The integration base for this mission is `Factory-droid`.
- Every remediation slice is a narrow packet that must trace back to `Factory-droid`.
- Workers never push, merge, or rewrite history; they return to the orchestrator when a packet is ready for base-branch review or merge action.

## Packet Order

1. Preserve current docs/executable-spec work on a docs-only branch or dedicated docs-only commit.
2. Sync local `Factory-droid`.
3. Run the preflight review on the already-merged auth/company/admin hardening.
4. Close the merge-gate regressions as a narrow packet.
5. Run Lane 01 as separate packet work.
6. Run Lane 02 as separate packet work.

Current continuation state:
- Steps 1-4 are sealed and already evidenced on `Factory-droid`.
- Lane 01 packet work is complete and its release-gate / handoff packet is now assembled for orchestrator base-branch review.
- Lane 02 can start only after that orchestrator review accepts Lane 01 as review-ready.

## Required Packet Controls

- Use `docs/code-review/executable-specs/PACKET-TEMPLATE.md` for packet shape.
- Use `docs/code-review/executable-specs/VALIDATION-FIRST-BUNDLE.md` for `prove first` findings such as `TEN-09`.
- Use `docs/code-review/executable-specs/RELEASE-GATE.md` before merge recommendation.
- Update `docs/frontend-update-v2/**` alongside `.factory/library/frontend-handoff.md` whenever a packet changes or explicitly confirms frontend-relevant behavior.
- Record implementer, reviewer, QA owner, and release approver for each packet.
- Record rollback notes before the packet is recommended for merge.

## When To Return To The Orchestrator

- A packet is ready for base-branch review or merge action.
- The feature requires branch creation, branch sync, PR preparation, or merge handling.
- The packet starts mixing unrelated lane boundaries.
- The release gate is blocked by missing proof, rollback notes, or parity evidence.
- A validation-first finding still lacks the required bundle.

## Mission-Specific Notes

- Keep `Factory-droid` as the base when reviewing or comparing packet scope.
- Do not let Lane 02 consume Lane 01 work until Lane 01 has its release-gate evidence.
- Keep Flyway work on `db/migration_v2` only.
- The prior Lane 01 HOLD/SUSPENDED lifecycle/runtime semantics drift is now closed in code; remaining risk is governance-only until orchestrator review accepts the release gate.
