# Review Queue Policy (Async-Loop)

Last reviewed: 2026-02-18
Owner: Orchestrator Agent
Status: Active

This policy defines behavior when code-review subagent dispatch is available vs blocked.

## 1) Scope
- Applies to code commits in async-loop execution.
- Lane-qualified docs-only review skip policy:
  - `fast_lane` docs-only commits run `bash ci/lint-knowledgebase.sh` and do not require review subagent dispatch.
  - `strict_lane` control-plane docs commits (`docs/agents/`, `docs/ASYNC_LOOP_OPERATIONS.md`, `docs/system-map/REVIEW_QUEUE_POLICY.md`, `agents/orchestrator-layer.yaml`, `asyncloop`, `scripts/harness_orchestrator.py`, `ci/`) may skip review subagent dispatch only when no runtime/config/schema/test files changed, and must run:
    - `bash ci/lint-knowledgebase.sh`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
  - resolve lane/check mapping with `scripts/harness_orchestrator.py`.

## 2) Normal mode (review available)
1. Make bounded change.
2. Run required validation for that risk slice.
3. Commit.
4. Dispatch review subagent for the code commit.
5. If findings exist, fix and re-run validations before next slice.

## 3) Saturation mode (review blocked by agent capacity)
When dispatch fails with capacity blockers (for example: `agent thread limit reached (max 6)`):
1. Continue only bounded slices with explicit proof.
2. Record per-slice evidence in `asyncloop`:
  - changed files
  - commands run
  - test/gate outcomes
  - blocked reason
  - pending review queue SHAs
3. Retry review-subagent dispatch at least once per slice cycle.
4. Do not claim final async-loop closure until queued code commits receive reviewer outcomes.

## 4) Minimum proof baseline in saturation mode
- For code-test slices: targeted tests for touched behavior.
- For risky runtime slices: relevant gate lane(s) and changed-file coverage when applicable.
- For staging closure evidence: anchored `gate_fast` + `gate_core` + `gate_reconciliation` + `gate_release`.

## 5) Queue accounting contract
- Maintain a single pending code-review queue in `asyncloop`.
- Add new code commit SHA immediately after commit.
- Remove SHA only after reviewer outcome is recorded (`findings` or `no-findings`).

## 6) Final closure criteria
- All required ledger gates pass on the same HEAD evidence set.
- Pending review queue is empty.
- No unresolved high/critical reviewer findings remain.
