#!/usr/bin/env bash
set -euo pipefail

tmux ls
echo 'attach: tmux attach -t w1'
echo 'attach: tmux attach -t w2'
