# TKT-ERP-STAGE-006 Agent Dispatch Messages

Target: `M18-S3A` canonical workflow path closure (smallest shippable) for O2C/P2P/production/payroll.
Base branch: `harness-engineering-orchestrator`.
Rule: each agent stays inside its task packet and write boundary only.

## SLICE-01 -> release-ops

YAML contract to follow:
- `agents/release-ops.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/release-ops`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/release-ops
codex exec -m gpt-5.3-codex -c reasoning_effort="high" --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are release-ops.
Start your response exactly with: I am release-ops and I own SLICE-01.

Read and obey:
- .harness/TASK_PACKET.md
- agents/release-ops.agent.yaml

Feature scope:
- Add/adjust CI guard logic enforcing canonical workflow write-path decisions for O2C/P2P/production/payroll.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Reviewers are review-only; do not modify reviewer files.
- Keep patch minimal and deterministic.

Required checks:
- bash scripts/guard_workflow_canonical_paths.sh
- bash ci/check-enterprise-policy.sh
- bash ci/check-architecture.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```

## SLICE-02 -> repo-cartographer

YAML contract to follow:
- `agents/repo-cartographer.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/repo-cartographer`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/repo-cartographer
codex exec -m gpt-5.3-codex -c reasoning_effort="medium" --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are repo-cartographer.
Start your response exactly with: I am repo-cartographer and I own SLICE-02.

Read and obey:
- .harness/TASK_PACKET.md
- agents/repo-cartographer.agent.yaml

Feature scope:
- Publish canonical write-path + alias/deprecation decisions for O2C/P2P/production/payroll and align endpoint inventory/repo overview docs.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Reviewers are review-only; do not modify reviewer files.
- Do not change runtime code.

Required checks:
- bash ci/lint-knowledgebase.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
