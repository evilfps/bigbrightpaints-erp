#!/usr/bin/env bash
set -euo pipefail

# Ticket TKT-ERP-STAGE-096
tmux send-keys -t w1 'cd /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-096/release-ops' Enter
tmux send-keys -t w1 'cat .harness/TASK_PACKET.md' Enter
tmux send-keys -t w1 'printf "\n# Paste TASK_PACKET prompt into the assigned agent CLI in this lane.\n"' Enter

tmux send-keys -t w2 'cd /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-096/repo-cartographer' Enter
tmux send-keys -t w2 'cat .harness/TASK_PACKET.md' Enter
tmux send-keys -t w2 'printf "\n# Paste TASK_PACKET prompt into the assigned agent CLI in this lane.\n"' Enter

