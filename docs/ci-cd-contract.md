# CI/CD Contract

Last reviewed: 2026-04-24

This repository keeps CI fast by routing pull requests through changed-file impact classes and parallel test manifests. CI should answer one clear question at each lane: whether the change is safe to continue, merge, integrate, or release. It should not run agent/governance ceremony as normal product ship proof.

## Pull Request Lane

Pull requests are the merge-confidence lane. The required signal is:

- `CI Config Check` for GitHub Actions and changed shell-script syntax quality.
- `Docs Lint` for canonical documentation consistency.
- `Module Boundary Check` for real module import boundaries.
- `High-Risk Change Control` for auth, company, RBAC, HR, accounting, orchestrator, and Flyway v2 migration changes.
- `Secrets Scan` for leaked credentials in the PR diff.
- `Change Impact Router` to select the required parallel shards.
- `Compile Check` to prove the backend packages.
- Routed product shards for access/tenant, accounting/finance, idempotency/outbox, workflow integration, persistence, and CODE-RED risk classes.
- `Changed-Code Coverage` to prove touched Java runtime lines are covered by the routed shards.
- `PR Ship Gate` to summarize upstream blockers by class.

The PR lane should stay around the current fast parallel runtime. If a check does not prove product behavior, changed-code confidence, secrets safety, or high-risk approval state, it does not belong in the required PR path.

## Main, Release, And Quality Lanes

- `Gate Core` runs after merge on `main`; it is the stronger integration lane with broader truthsuite and module coverage proof.
- `Gate Release` and `Gate Reconciliation` run on tags or manual release validation; they prove migration, deployability, reconciliation, and release evidence.
- `Gate Quality` is scheduled/manual only; mutation and flake windows do not block ordinary PRs.
- The separate `Release` workflow only writes GitHub release notes and does not prove deployability.

## Failure Classification

CI failure messages should point engineers to the first useful class of blocker:

- `ci-config`: invalid workflow configuration or changed shell-script lint failure.
- `compile`: Java/package failure.
- `product-tests`: routed shard failure.
- `changed-code-coverage`: missing JaCoCo artifacts, vacuous coverage, skipped changed source, unmapped changed lines, or threshold failure.
- `high-risk-control`: missing R2 approval, rollback, migration docs, or high-risk test evidence.
- `secrets`: leaked secret finding.
- `docs` or `module-boundary`: documentation or module boundary drift.

`PR Ship Gate` is only a summary. Fix the upstream job first.

## Required Check Cutover

When this branch merges, branch protection for `main` must require the current product-readable checks and remove retired wrapper names in the same settings change. The GitHub API uses the bare check-run context names below. The GitHub web UI may display them grouped under the workflow, such as `CI / CI Config Check`; select the same underlying check names after the first run of this workflow.

Required pull-request status-check API contexts:

- `CI Config Check`
- `Docs Lint`
- `Module Boundary Check`
- `High-Risk Change Control`
- `Secrets Scan`
- `Change Impact Router`
- `Compile Check`
- `Changed-Code Coverage`
- `PR Ship Gate`

Required routed shard checks stay conditional and should not be listed as unconditional branch-protection requirements:

- `Access And Tenant Tests`
- `Finance And Accounting Tests`
- `Idempotency And Outbox Tests`
- `Workflow Integration Tests`
- `Persistence Smoke`
- `CODE-RED Access Tests`
- `CODE-RED Finance Tests`

Retired required-check contexts to remove from branch protection:

- `pr-merge-gate`
- `codex-review-policy`
- `Security Review Gate`
- `enterprise-policy-check`
- `Enterprise Policy Check`
- `orchestrator-layer-check`
- `Orchestrator Layer Check`
- `Codex Review`
- `Codex Autofix`

Cutover command for the canonical `origin` repository:

```bash
gh api --method PATCH \
  repos/evilfps/bigbrightpaints-erp/branches/main/protection/required_status_checks \
  -F strict=false \
  -F contexts[]='CI Config Check' \
  -F contexts[]='Docs Lint' \
  -F contexts[]='Module Boundary Check' \
  -F contexts[]='High-Risk Change Control' \
  -F contexts[]='Secrets Scan' \
  -F contexts[]='Change Impact Router' \
  -F contexts[]='Compile Check' \
  -F contexts[]='Changed-Code Coverage' \
  -F contexts[]='PR Ship Gate'
```
