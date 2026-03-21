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
