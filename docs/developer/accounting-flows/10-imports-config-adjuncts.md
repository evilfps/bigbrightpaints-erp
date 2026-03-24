# Accounting Imports, Configuration, and Adjacent Truth Surfaces

## Folder Map

- `modules/accounting/controller|service|domain|dto`
  Purpose: opening balances, Tally import, default-account setup, payroll entrypoints.
- `modules/production/controller|service|domain|dto`
  Purpose: accounting-aware catalog maintenance and product account metadata.
- `modules/inventory/controller|service|domain|dto`
  Purpose for this slice: accounting-prefixed raw-material surfaces.
- `core/health`
  Purpose: configuration completeness checks.

## Major Workflows

### Opening Balance Import

- entry: `OpeningBalanceImportController`
- canonical path:
  - validate CSV rows
  - resolve or create accounts
  - persist import record
  - post one opening journal

### Tally Import

- entry: `TallyImportController`
- canonical path:
  - dedupe by file hash
  - parse XML
  - resolve or create accounts
  - synthesize opening-balance rows
  - hand off to opening-balance importer

### Default Accounts Setup

- entry: `AccountingController.getDefaultAccounts/updateDefaultAccounts`
- canonical service: `CompanyDefaultAccountsService`

### Catalog Maintenance

- entry: `AccountingCatalogController`
- canonical service: `ProductionCatalogService`
- key effect:
  - create/update products
  - sync finished goods and raw materials
  - seed account metadata

### Configuration Health

- entry: `AccountingConfigurationController`
- canonical service: `ConfigurationHealthService`
- role: read-only diagnostics

## What Works

- opening balances and Tally imports converge onto the same journal-posting path
- config health is read-only and separate from write flows
- default-account service is a central setup choke point

## Duplicates and Bad Paths

- `AccountingCatalogController` overlaps generic production catalog ownership
- `PayrollController` overlaps HR payroll lifecycle and accounting single-payment path
- `CompanyAccountingSettingsService.requirePayrollDefaults/updatePayrollDefaults` look latent compared with the live tax-account path
- `RawMaterialService` exposes shortcut flows while warning that purchasing is canonical
- `ProductionCatalogService` mutates product, raw-material, and finished-good truth together and is a strong ownership seam

## Review Hotspots

- `ProductionCatalogService.importCatalog`
- `ProductionCatalogService.ensureFinishedGoodAccounts`
- `OpeningBalanceImportService.importOpeningBalances`
- `OpeningBalanceImportService.postOpeningBalanceJournal`
- `TallyImportService.importTallyXml`
- `CompanyDefaultAccountsService.updateDefaults`
- `AccountingCoreEngineCore.processPayrollBatchPayment`
