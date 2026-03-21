# Catalog Consolidation Scope And Definition Of Done

This file is the handoff packet for a separate team to execute in parallel with
non-overlapping ERP work.

## Why This Is Parallel-Safe

This packet mainly changes:

- catalog route ownership
- product and SKU write orchestration
- inventory mirror guarantees
- developer/docs/OpenAPI/test truth around those surfaces

It should not overlap with:

- accounting report cleanup
- settlement or ledger redesign
- payroll / HR cleanup
- period-close logic
- runtime-control-plane work

## In Scope

- choose one canonical public catalog host
- remove catalog wrapper drift across accounting, catalog, and production
  controller families
- unify single-product and multi-variant create behind one canonical endpoint
- unify SKU generation rules
- support existing-brand selection and inline new-brand creation
- add explicit product-family or variant-group persistence
- guarantee finished-good or raw-material mirror readiness in the same create
  path
- keep product search/list under the same canonical host
- rewrite stale tests and docs in the same packet
- update OpenAPI in the same packet

## Out Of Scope

- reports
- journal / settlement cleanup
- dealer credit redesign
- payroll / HR redesign
- inventory valuation redesign
- factory costing redesign
- unrelated product-pricing policy changes

## Definition Of Done

### Public Surface

- `/api/v1/catalog/**` is the only supported public catalog host
- `/api/v1/accounting/catalog/**` no longer survives as a supported public
  catalog host
- `/api/v1/production/**` no longer survives as a competing product-browse
  host

### Write Path

- one canonical create endpoint exists for both single and matrix create
- one canonical write engine owns SKU generation and downstream mirror logic
- brand select and inline brand create both work

### Data Model

- product-family or variant-group linkage is explicit and persisted
- grouped SKU membership no longer relies only on naming convention

### Downstream Readiness

- finished-good create also creates or updates finished-good inventory truth
- raw-material create also creates or updates raw-material inventory truth
- production/factory can select the product without manual repair
- sales can add the SKU to orders without missing-finished-good failure

### UX

- one screen can create one SKU or many variants
- preview exists before commit
- duplicate and conflict failures are explicit
- delimiter-based quick input stays UI-only sugar

### Cleanup

- stale tests for old hosts are deleted or rewritten
- stale OpenAPI surfaces are removed
- developer docs and handoff docs match runtime truth

## Required Proof

- create with existing brand passes
- create with inline new brand passes
- `1 x 1 -> 1 SKU`
- `4 x 4 -> 16 SKUs`
- variant grouping persists
- finished-good mirror creation proves out
- raw-material mirror creation proves out
- sales order SKU resolution works immediately after create
- production/factory selection works immediately after create
- OpenAPI snapshot matches the new surface
- `git diff --check` is clean

## Suggested Issue Text

Title:

`Catalog Surface Consolidation: one canonical product-entry flow with guaranteed downstream readiness`

Summary:

Replace the split accounting/catalog/production product flow with one canonical
`/api/v1/catalog/**` surface and one canonical write engine. The new flow must
support existing-brand selection, inline brand creation, single-SKU creation,
and matrix variant creation from one screen. Every created SKU must be
immediately ready for downstream production, inventory, and sales use without
manual repair.

Acceptance highlights:

- one canonical public host
- one canonical write service
- one create endpoint for single or matrix create
- explicit variant grouping
- guaranteed downstream mirror creation
- sales and production can consume the new SKU without manual repair
