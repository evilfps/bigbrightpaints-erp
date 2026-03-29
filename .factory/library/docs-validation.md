# Docs Validation

Docs-only validation guidance for the backend truth-library mission.

**What belongs here:** docs validator commands, evidence expectations, and full-contract lint notes.

---

## Primary Commands

- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-enterprise-policy.sh`
- `bash ci/check-architecture.sh`
- `bash ci/check-orchestrator-layer.sh`
- `bash scripts/guard_openapi_contract_drift.sh`

## Docs-Only Validation Rules

- Do not start application services for docs-only validation
- Prefer file/link/inventory proof over runtime proof
- Use `openapi.json` as the public API snapshot when checking routes/payload families
- Verify that canonical docs carry `Last reviewed:` markers when required by lint
- When a packet references a deprecated surface, verify it also points to a replacement or explicitly says none exists

## Full-Contract Lint Expectations

`ci/lint-knowledgebase.sh` should pass in full-contract mode by the end of the mission. The docs tree must therefore include the lint-required canonical docs/governance files, not just compatibility-mode files.

## Expected Evidence

- exact file paths edited
- exact validator commands run
- sampled internal links checked
- sampled route/host/role claims cross-checked against `openapi.json` and source
- any contradictions or open decisions discovered during documentation work
