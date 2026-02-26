# Ticket Template

## Metadata
- ticket_id: `<TKT-ID>`
- title: `<title>`
- goal: `<goal>`
- priority: `<high|medium|low>`
- status: `planned`
- base_branch: `<base branch; defaults to current git branch when bootstrapped>`

## Slices
For each slice capture:
- primary_agent
- reviewers
- lane
- branch
- worktree_path
- allowed_scope_paths
- objective
- current_failure
- expected_behavior
- constraints
- safe_assumptions
- assumptions_to_validate
- required_checks
- status

Reviewer contract for non-doc slices:
- `code-reviewer` (deep module review)
- `merge-specialist` (integration integrity + merge decision)
- `qa-reliability` (system-level exploratory validation)

## Definition Of Done
- required checks pass
- reviewer evidence present and approved
- no scope violations
- merge completed (if merge mode enabled)
