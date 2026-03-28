# R2 Checkpoint

## Scope
- Feature: `ERP-46 post-merge review followups`
- Branch: `fix/erp-46-postmerge-review-followups`
- PR: `#169`
- Baseline already merged to `main`: `#168` at merge commit `0986396681c66686dad01ffde302a0a6d600b4b4`
- Review candidate:
  - restore `CompanyService.create(...)` to a public `@Transactional` entrypoint so tenant bootstrap keeps an explicit proxy-safe transaction boundary
  - harden `CompanyControllerTest` so any declared `delete(...)` route on `CompanyController` fails the test, not just a no-arg overload
  - make `ProductionCatalogServiceRetryPolicyTest` use UTF-8 CSV fixture bytes so catalog idempotency hashing remains platform-stable
  - refresh `docs/code-review/flows/company-tenant-control-plane.md` so the review note matches the actual runtime contract on `main`
- Why this is R2: the follow-up touches `CompanyService.java`, which is still a tenant/control-plane runtime surface. A wrong fix here could quietly weaken tenant-bootstrap transactional guarantees even though the original Wave 2 merge is already on `main`.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/controller/CompanyControllerTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogServiceRetryPolicyTest.java`
  - `docs/code-review/flows/company-tenant-control-plane.md`
- Contract surfaces affected:
  - tenant bootstrap transaction boundary in `CompanyService.create(...)`
  - regression protection for the retired company delete surface
  - catalog import idempotency/hash test determinism
  - published review/approval evidence for the company and tenant control plane
- Failure mode if wrong:
  - tenant bootstrap could execute without the intended transactional interception boundary
  - a future `delete(...)` route on `CompanyController` could slip past the regression test
  - catalog hash tests could pass or fail depending on platform default charset
  - review docs could continue to claim retired `/api/v1/companies/{id}/...` control-plane routes are live

## Approval Authority
- Mode: human
- Approver: `ERP-46 owner`
- Canary owner: `ERP-46 owner`
- Approval status: `pending human review; post-merge follow-up proof green`
- Basis: even though this is a narrow follow-up, it still changes a live tenant/control-plane service class and therefore stays inside the R2 approval lane.

## Escalation Decision
- Human escalation required: yes
- Reason: `CompanyService.create(...)` remains part of the tenant bootstrap/control-plane path.

## Rollback Owner
- Owner: `ERP-46 owner`
- Rollback method:
  - before merge: close PR `#169` and drop branch `fix/erp-46-postmerge-review-followups`
  - after merge: revert only the follow-up commit so the original Wave 2 merge on `main` remains intact
  - do not partially revert the doc/test changes without reverting the `CompanyService.create(...)` visibility change in the same rollback
- Rollback trigger:
  - tenant bootstrap loses its expected transactional behavior
  - `CompanyControllerTest` no longer catches a reintroduced `delete(...)` method
  - catalog retry policy tests show charset-sensitive hashing behavior
  - the updated control-plane review note drifts again from the live runtime contract

## Expiry
- Valid until: `2026-04-04`
- Re-evaluate if: this follow-up expands beyond the 4 review findings or another PR reopens tenant/control-plane runtime changes on top of it.

## Verification Evidence
- Commands run:
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-46-wave-2-integration/erp-domain && mvn -B -ntp -Dtest=CompanyControllerTest,CompanyServiceTest,CompanyControllerIT,ProductionCatalogServiceRetryPolicyTest test`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-46-wave-2-integration && git diff --check`
- Result summary:
  - targeted proof passed with `Tests run: 106, Failures: 0, Errors: 0, Skipped: 0`
  - `CompanyControllerIT` passed with `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
  - `CompanyControllerTest` passed with `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
  - `CompanyServiceTest` passed with `Tests run: 83, Failures: 0, Errors: 0, Skipped: 0`
  - `ProductionCatalogServiceRetryPolicyTest` passed with `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`
  - `git diff --check` passed clean
- Artifacts/links:
  - repo checkout: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-46-wave-2-integration`
  - module path: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-46-wave-2-integration/erp-domain`
  - merged baseline PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/168`
  - follow-up PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/169`
