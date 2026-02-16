# Security and Governance

Last reviewed: 2026-02-15
Owner: Security & Governance Agent

## Initial Risk Register (Phase A)

| Risk area | Why high risk | Current signals | Immediate control |
| --- | --- | --- | --- |
| Accounting posting integrity | Ledger corruption creates financial misstatement risk | Dense posting paths across sales/inventory/purchasing/hr | Require targeted truth tests + reconciliation checks before merge |
| Payroll + HR posting | Payroll and liabilities involve sensitive calculations and legal exposure | Payroll flows post into accounting and period-close surfaces | Mandatory human approval for payroll posting semantic changes |
| AuthZ / RBAC / tenant isolation | Broken scope checks can expose cross-company data | Multiple module controllers rely on company context and role checks | Block completion unless authz tests and tenant boundary checks pass |
| Data migrations | Schema mistakes can break posting, reporting, or startup | Active Flyway v2 chain + drift/overlap guard scripts | Use staged migration checklist + rollback drill before prod |
| PII and credentials | User/account/contact data and tokens exist in auth domain | User account + tokens + mail templates + logs | Redaction policy + least-privilege access + secret scanning |

## PII and Logging Rules
- Never log full credentials, tokens, reset links, MFA secrets, or raw personal identifiers when avoidable.
- Prefer structured logs with sanitized fields (`userId`, `companyId`, opaque `traceId`).
- Redaction minimums:
  - emails: mask local-part except first 2 chars
  - phone numbers: keep last 2-4 digits only
  - tokens/keys: do not log, even truncated in production paths
- If a debug log is required for incident response, gate it behind explicit non-prod config and expiry TODO.

## Secret Handling Expectations
- No plaintext secrets in git-tracked files.
- `.env` is local-only operational convenience, not source-of-truth secret storage.
- CI secrets must come from platform secret store (GitHub Actions secrets or equivalent).
- Rotation policy: unspecified.
  - TODO: define concrete rotation cadence and owners in ops runbook.

## Least-Privilege Guidance
- CI tokens:
  - default `contents: read`; grant write only where workflow requires it.
  - short-lived tokens for release/deploy jobs.
- Repository automation:
  - isolate doc/lint bots from deploy credentials.
- Kubernetes RBAC (if applicable):
  - use namespace-scoped service accounts per environment.
  - deny wildcard verbs/resources for app workloads.
  - prod deploy privilege requires human approval.
- Kubernetes usage in this repo: unspecified.
  - TODO: link actual cluster manifests/policies if Kubernetes is used.

## Escalation Rules (Human Required)
- Production DB migrations.
- Permission expansions (role scope widening, new privileged endpoints, tenant scope changes).
- Auth/session/JWT behavior changes.
- Payroll and ledger posting semantic changes.
- Destructive data operations.

## Supply Chain (SBOM, Provenance, Signing)
- SBOM generation: template command (Maven CycloneDX plugin) is currently unspecified in repo.
  - TODO: add plugin + CI step to publish SBOM artifact.
- Provenance attestations: unspecified.
  - TODO: define SLSA/in-toto strategy for release artifacts.
- Artifact signing: unspecified.
  - TODO: choose cosign/sigstore or enterprise signing standard.

## Docker Security Baseline (Detected)
Docker is used via `docker-compose.yml` and `erp-domain/Dockerfile`.

Minimum guidance:
- Run container with non-root user (currently unspecified in Dockerfile).
  - TODO: add non-root user and filesystem permissions.
- Pin base images to digest in release hardening phase.
- Set read-only root FS where practical.
- Avoid embedding fixed secrets in compose defaults for non-local environments.

## Audit Logging Expectations
- High-risk actions must emit actor, company, operation, outcome, and trace id.
- Audit logs should be immutable at storage layer (implementation detail unspecified).
  - TODO: document retention and tamper-evidence controls.
