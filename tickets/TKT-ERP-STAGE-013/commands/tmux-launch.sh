#!/usr/bin/env bash
set -euo pipefail

# Ticket TKT-ERP-STAGE-013
tmux send-keys -t w1 'cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-013/accounting-domain' Enter
tmux send-keys -t w1 'cat .harness/TASK_PACKET.md' Enter
tmux send-keys -t w1 'printf "\n# Paste TASK_PACKET prompt into the assigned agent CLI in this lane.\n"' Enter

tmux send-keys -t w2 'cd /home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-013/refactor-techdebt-gc' Enter
tmux send-keys -t w2 'cat .harness/TASK_PACKET.md' Enter
tmux send-keys -t w2 'printf "\n# Paste TASK_PACKET prompt into the assigned agent CLI in this lane.\n"' Enter

