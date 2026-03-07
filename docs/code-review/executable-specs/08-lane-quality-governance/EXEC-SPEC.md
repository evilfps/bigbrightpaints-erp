# Lane 08 Exec Spec

## Covers
- Backlog row 8
- `QA-01`, `QA-02`, `QA-03`, `QA-04`, `QA-05`, `QA-06`, `QA-07`, `QA-08`, `QA-09`, `QA-10`, `SA-01`, `SA-03`, `SA-04`

## Why This Lane Matters Early
Without hard quality gates, the remediation wave itself can add fresh debt or regressions while claiming improvement.

## Primary Review Sources
- `test-ci-governance.md`
- `static-analysis-triage.md`
- `01-confidence-lanes-and-deployability-contract.md`

## Primary Execution Surfaces
- CI workflows and gate scripts
- baseline artifacts for static analysis
- OpenAPI and changed-files enforcement
- CODE-RED, invariant, and smoke test lanes

## Entry Criteria
- the current CI lanes, changed-files checks, OpenAPI checks, and baseline artifact locations are inventoried
- owners are assigned for CODE-RED, invariant, smoke, and static-analysis governance
- no hotspot cleanup or style refactor is sharing the same first packet
- the branch has a place to publish machine-readable baseline artifacts

## Produces For Other Lanes
- hard merge gates that stop the remediation wave from adding new debt
- one agreed gate matrix for lane owners and release owners
- a baseline that separates legacy noise from new regressions

## Packet Sequence

### Packet 0 - capture the baseline
- record the current static-analysis and quality-debt baseline in a machine-readable artifact
- define which suites are advisory, which are blockers, and who owns each lane
- output: baseline artifact and gate matrix

### Packet 1 - harden the critical-path tests
- promote CODE-RED, invariant, and smoke suites from advisory to required where they guard the production paths under repair
- keep ownership explicit so failures route to the right lane
- keep the confidence-by-lane contract explicit so local, PR, main, staging, and canary do not all pretend to do the same job
- output: hard-gate packet for critical-path tests

### Packet 2 - fail closed on contract and changed-files checks
- make OpenAPI parity and changed-files enforcement block merges for touched surfaces
- ensure the checks are narrow enough to be trusted and not ignored as noise
- output: contract-governance packet

### Packet 3 - enforce new-violations-only analysis and observe flakes
- wire Qodana or equivalent static-analysis comparison against the captured baseline
- keep flake detection and broad E2E evidence as a ratchet, not the wrong blocker for unrelated lanes
- output: static-analysis governance packet

## Frontend And Operator Handoff
- every lane owner can point to the exact gate bundle they must satisfy before asking frontend or operators to trust a change
- release owners get one matrix of required checks by lane
- quality gates must be understandable enough that teams fix failures instead of blindly rerunning them

## Stop-The-Line Triggers
- hotspot or style cleanup starts before the baseline exists
- flaky suites are turned into blockers for unrelated slices without classification
- OpenAPI or changed-files checks stay advisory for routes that are actively changing
- gate changes merge without an owner, baseline artifact, and failure-routing note

## Must Not Mix With
- hotspot refactors that change product behavior
- unrelated style cleanup before baseline exists

## Must-Pass Evidence
- baseline artifact committed or published for the branch
- CI lane definitions for CODE-RED, invariants, smoke, and changed-files checks
- proof that new-violations-only enforcement does not fail the whole legacy backlog

## Rollback
- revert the hard-gate toggle while keeping the captured baseline artifacts intact

## Exit Gate
- new remediation work cannot merge without the intended critical-path tests and contract checks
- static-analysis governance distinguishes new defects from legacy backlog noise
