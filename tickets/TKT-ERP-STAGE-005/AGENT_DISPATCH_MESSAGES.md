# TKT-ERP-STAGE-005 Agent Dispatch Messages

Target: `M18-S2A` tenant hold/block runtime enforcement.
Base branch: `harness-engineering-orchestrator`.
Rule: each agent stays inside its task packet and write boundary only.

## SLICE-01 -> auth-rbac-company

YAML contract to follow:
- `agents/auth-rbac-company.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-005/auth-rbac-company`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-005/auth-rbac-company
codex exec --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are auth-rbac-company.
Start your response exactly with: I am auth-rbac-company and I own SLICE-01.

Read and obey:
- .harness/TASK_PACKET.md
- agents/auth-rbac-company.agent.yaml

Feature scope:
- M18-S2A tenant hold/block controls with super-admin authority and immutable audit metadata.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Reviewers are review-only; do not modify reviewer files.
- Fail closed on missing authority or tenant context.

Required checks:
- cd erp-domain && mvn -B -ntp -Dtest='AuthTenantAuthorityIT,*Company*' test
- bash ci/check-architecture.sh
- bash ci/check-enterprise-policy.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```

## SLICE-02 -> refactor-techdebt-gc

YAML contract to follow:
- `agents/refactor-techdebt-gc.agent.yaml`

Worktree:
- `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-005/refactor-techdebt-gc`

Command template:
```bash
cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-005/refactor-techdebt-gc
codex exec --dangerously-bypass-approvals-and-sandbox "<paste prompt below>"
```

Prompt:
```text
You are refactor-techdebt-gc.
Start your response exactly with: I am refactor-techdebt-gc and I own SLICE-02.

Read and obey:
- .harness/TASK_PACKET.md
- agents/refactor-techdebt-gc.agent.yaml

Feature scope:
- Runtime fail-closed enforcement for hold/blocked tenant state in company context security flow.

Constraints:
- Stay inside scope_paths and allowed_scope_paths from the task packet.
- Keep unauthenticated behavior at 401 and tenant-state denial at deterministic 403 after auth context.
- No unrelated refactors.

Required checks:
- cd erp-domain && mvn -B -ntp -Dtest='AuthTenantAuthorityIT' test
- bash ci/check-architecture.sh
- bash ci/check-enterprise-policy.sh

Return strict output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
