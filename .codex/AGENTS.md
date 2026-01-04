## Async Execution Mode (required when running tasks/* epics)
When executing a task file under /tasks:
- Treat the task file as the source of truth.
- Do not ask questions unless there is a hard ambiguity that blocks compilation or correctness.
- If ambiguity exists, follow the task’s "Fallback Decisions" section. If none exists, choose the option that:
  1) matches existing code patterns
  2) minimizes schema risk
  3) is easiest to reverse
  and document the assumption in the epic notes.

## Never Stop Early
You may only stop when ALL are true:
- all verification gates pass (typecheck, lint, full tests)
- required reconciliation tests for the epic pass
- docs/runbook updates are complete
- the service builds and starts in a prod-like configuration (as described in the task)
If any fail, continue iterating until they pass.

## Output Required at End of an Epic
- milestones completed + commits
- files changed
- commands executed + results
- reconciliation evidence (what matched what)
- deployment readiness checklist results
- remaining known risks (should be none for core invariants)
## Mandatory Handoff (HYDRATION.md)
At the end of every epic (or on blocker/stop), update HYDRATION.md with:
- completed epics + branch names + commit SHAs
- current epic + next milestone to run
- commands run + results
- known warnings/stabilization notes
- exact resume instructions (next task file + branch)

DONT MAKE ANY WORKTREE'S WORK HERE ONLY
