# Developer Docs

This folder contains durable engineering docs for understanding ERP workflows,
module ownership, and target-state cleanup plans.

## Index

- [accounting-flows/README.md](./accounting-flows/README.md)
  Current-state accounting and cross-module workflow maps.
- [catalog-consolidation/README.md](./catalog-consolidation/README.md)
  Catalog cleanup packet overview with current-state truth, target flow,
  definition of done, and issue-ready handoff text.
- [catalog-consolidation/01-current-state-flow.md](./catalog-consolidation/01-current-state-flow.md)
  Deep map of today's catalog controllers, services, persistence, and
  downstream inventory / production / sales behavior.
- [catalog-consolidation/02-target-accounting-product-entry-flow.md](./catalog-consolidation/02-target-accounting-product-entry-flow.md)
  Simplified end-to-end accounting team UX and canonical API flow for product
  and SKU creation.
- [catalog-consolidation/03-definition-of-done-and-parallel-scope.md](./catalog-consolidation/03-definition-of-done-and-parallel-scope.md)
  Parallel-safe execution boundary, acceptance criteria, proof targets, and
  issue handoff copy.
- [catalog-consolidation/04-update-hygiene.md](./catalog-consolidation/04-update-hygiene.md)
  Rules for keeping these developer docs, OpenAPI, tests, and route ownership
  docs in sync as the catalog surface changes.
- [onboarding-stock-readiness/README.md](./onboarding-stock-readiness/README.md)
  Hard-cut setup-flow packet covering tenant bootstrap, company defaults,
  stock-bearing product setup, opening stock, and per-SKU readiness truth.
- [onboarding-stock-readiness/01-current-state-flow.md](./onboarding-stock-readiness/01-current-state-flow.md)
  Current runtime ownership from onboarding through readiness visibility.
- [onboarding-stock-readiness/02-route-service-map.md](./onboarding-stock-readiness/02-route-service-map.md)
  Canonical route, service, and persistence map for setup readiness.
- [onboarding-stock-readiness/03-target-simplified-user-flow.md](./onboarding-stock-readiness/03-target-simplified-user-flow.md)
  Explicit operator journey and frontend/API ownership for setup.
- [onboarding-stock-readiness/04-delete-first-duplicates.md](./onboarding-stock-readiness/04-delete-first-duplicates.md)
  Delete-first seams that must stay retired.
- [onboarding-stock-readiness/05-definition-of-done-and-update-hygiene.md](./onboarding-stock-readiness/05-definition-of-done-and-update-hygiene.md)
  Scope, proof, stop rules, and doc hygiene for setup-flow changes.
