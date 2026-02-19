# TKT-ERP-STAGE-007 Agent Dispatch Messages

Target: `M18-S6A` GST/non-GST settlement posting drift guards.
Base branch: `harness-engineering-orchestrator`.
Rule: each agent stays inside its task packet and write boundary only.

## SLICE-01 -> accounting-domain

YAML contract to follow:
- `agents/accounting-domain.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-007/accounting-domain`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-007/accounting-domain
codex exec --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are accounting-domain.
Start your response exactly with: I am accounting-domain and I own SLICE-01.

Read and obey:
- .harness/TASK_PACKET.md
- agents/accounting-domain.agent.yaml

Feature scope:
- Enforce GST/non-GST settlement posting drift guards in accounting boundary with fail-closed reconciliation-safe behavior.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Reviewers are review-only; do not modify reviewer files.
- No unrelated refactors.

Required checks:
- cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test
- bash scripts/verify_local.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```

## SLICE-02 -> purchasing-invoice-p2p

YAML contract to follow:
- `agents/purchasing-invoice-p2p.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-007/purchasing-invoice-p2p`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-007/purchasing-invoice-p2p
codex exec --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are purchasing-invoice-p2p.
Start your response exactly with: I am purchasing-invoice-p2p and I own SLICE-02.

Read and obey:
- .harness/TASK_PACKET.md
- agents/purchasing-invoice-p2p.agent.yaml

Feature scope:
- Guard GST/non-GST purchasing side contracts so accounting settlement/posting cannot drift across tax modes.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Reviewers are review-only; do not modify reviewer files.
- Keep contract-first ordering with accounting downstream dependency.

Required checks:
- cd erp-domain && mvn -B -ntp -Dtest='TS_P2PPurchaseJournalLinkageTest,GstConfigurationRegressionIT' test
- bash ci/check-enterprise-policy.sh
- bash ci/check-architecture.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
