# Review Playbook

Shared guidance for review-only missions that generate analysis and documentation rather than code fixes.

**What belongs here:** Review quality bar, artifact expectations, evidence standards, and documentation conventions for production audits.

---

## Review-Only Boundary
- Prefer static code and configuration inspection first.
- Runtime probes should be passive and evidence-oriented.
- Do not mix review work with implementation fixes unless the orchestrator explicitly changes mission scope.

## Required Evidence Style
- Cite concrete paths: file, class, method, endpoint, script, workflow, or config key.
- Explain why the finding matters, not just what exists.
- Distinguish between confirmed issues and suspected hotspots.
- Record blast radius or affected workflow wherever possible.

## Flow Documentation Standard
Every flow document should be a narrative walkthrough covering:
- business purpose
- endpoint/controller entrypoints
- service/class chain
- entity/data path
- schema or migration touchpoints where relevant
- invariants and state assumptions
- integrations and side effects
- failure points and recovery behavior
- security, privacy, protocol/protection, observability, performance, and resilience notes

## Synthesis Standard
- `README.md` should link the full review set.
- `coverage-matrix.md` should show where each mandatory review angle is covered.
- `risk-register.md` should normalize severity, category, evidence, and affected surface.
- `remediation-backlog.md` should prioritize follow-up work by severity, exploitability, ERP integrity impact, operational risk, and sequencing.

## Static-Analysis Triage Standard
- Create `docs/code-review/static-analysis-triage.md` when the mission includes lint/static-analysis governance.
- Classify the historical backlog using severity buckets: `blocker`, `critical`, `major`, `minor`, `info`.
- Group issues by type: `security`, `correctness`, `nullability`, `performance`, `maintainability`, `style`.
- Include a rule-concentration section to show whether a large portion of the backlog is dominated by one or a few noisy rules.
- Recommend a survivable quality gate that baselines legacy debt, blocks blocker/security issues, blocks new critical issues, temporarily tolerates legacy minor issues, and fails only on new violations going forward.
