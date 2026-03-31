# Accounting States And Errors

Important states:

- COA seeded / COA missing
- onboarding bootstrap confirmed / onboarding bootstrap blocked
- default accounts configured / missing
- GST tax accounts configured / missing
- SKU readiness blocked on accounting configuration
- opening-stock import `QUEUED`, `PARTIAL_SUCCESS`, `SUCCEEDED`, `FAILED`
- period close request `PENDING`, `APPROVED`, `REJECTED`, `REOPENED`
- journal status `POSTED`, `REVERSED`
- reconciliation state `NOT_STARTED`, `IN_PROGRESS`, `COMPLETE`, `BLOCKED`
- settlement state `DRAFT`, `MATCHED`, `POSTED`, `FAILED`

Important readiness blockers:

- `COA_NOT_SEEDED`
- `DEFAULT_ACCOUNT_CONFIGURATION_INCOMPLETE`
- `FINISHED_GOOD_VALUATION_ACCOUNT_MISSING`
- `FINISHED_GOOD_COGS_ACCOUNT_MISSING`
- `FINISHED_GOOD_REVENUE_ACCOUNT_MISSING`
- `FINISHED_GOOD_DISCOUNT_ACCOUNT_MISSING`
- `FINISHED_GOOD_TAX_ACCOUNT_MISSING`
- `RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING`
- `GST_INPUT_ACCOUNT_MISSING`
- `GST_OUTPUT_ACCOUNT_MISSING`
- `GST_PAYABLE_ACCOUNT_MISSING`
- `TENANT_ACCOUNTING_BOOTSTRAP_MISSING`

Important user-visible failures:

- accounting portal blocked because onboarding never seeded tenant COA
- missing default accounts for product posting
- GST output account mismatch for taxable finished goods
- opening stock rejected because readiness blockers remain
- journal reverse rejected because the period is closed or the reference chain
  is invalid
- close request rejected because checklist or reconciliation is incomplete
- direct close rejected because it is not a supported frontend action
- settlement rejected because allocation totals do not match payment totals
- export blocked pending approval

UI rules:

- Finance blockers must be shown as explicit corrective messages, never as a
  generic failure banner.
- Bootstrap failures must route the user back to superadmin remediation instead
  of inviting manual COA reconstruction in the accounting shell.
- A stale link or stale client action targeting `/close`, legacy manual-journal
  create, or legacy reverse endpoints should fail loudly in development and be
  removed from the production UI.
- Loading and empty states must distinguish between missing data, missing
  permissions, and blocked onboarding prerequisites.
