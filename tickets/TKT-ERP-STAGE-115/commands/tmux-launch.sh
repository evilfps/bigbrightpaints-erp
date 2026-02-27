#!/usr/bin/env bash
set -euo pipefail

# Ticket TKT-ERP-STAGE-115
tmux send-keys -t w1 'cd /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-115/auth-rbac-company' Enter
tmux send-keys -t w1 'cat .harness/TASK_PACKET.md' Enter

tmux send-keys -t w2 'cd /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-115/frontend-documentation' Enter
tmux send-keys -t w2 'cat .harness/TASK_PACKET.md' Enter

tmux send-keys -t w3 'cd /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-115/qa-reliability' Enter
tmux send-keys -t w3 'cat .harness/TASK_PACKET.md' Enter
