# Workflows

## Tenant onboarding

1. Load `GET /api/v1/superadmin/tenants/coa-templates` before enabling submit.
2. Collect tenant identity, timezone, quota defaults, first-admin identity, and `coaTemplateCode`.
3. Submit `POST /api/v1/superadmin/tenants/onboard`.
4. Treat the mutation as complete only when:
   - `seededChartOfAccounts=true`
   - `defaultAccountingPeriodCreated=true`
   - `tenantAdminProvisioned=true`
5. If `defaultGstRate=0`, do not expect retained GST input/output/payable
   account bindings on the created tenant; non-GST onboarding clears those
   company-level GST links.
6. Redirect to `/platform/tenants/:tenantId` using the numeric tenant id from the
   superadmin tenant list or tenant-detail response.
7. Do not reuse that numeric tenant id as tenant-shell identity. Every
   tenant-scoped shell still boots from `GET /api/v1/auth/me` and `companyCode`.
8. Surface a warning banner if `systemSettingsInitialized=false` or any bootstrap truth flag is false.

## Lifecycle change

1. Enter from tenant detail.
2. Show current `lifecycleState` and `lifecycleReason`.
3. Require a non-empty reason for every transition.
4. Submit `PUT /api/v1/superadmin/tenants/{id}/lifecycle`.
5. Re-fetch tenant detail and append the change to the support timeline view.

## Limits and modules management

1. Load current `limits` and `enabledModules` from tenant detail.
2. Edit quota values or module set in separate forms.
3. Submit one mutation at a time.
4. Re-fetch tenant detail after each mutation and discard stale form state.

## Support recovery

1. Open tenant detail `Support` tab.
2. Review `supportContext`, `supportTimeline`, and `availableActions`.
3. Use the least destructive action first:
   - update support context
   - issue warning
   - reset tenant admin password
   - force logout
4. After mutation success, refresh tenant detail and keep the operator on the same tab.

## Main-admin replacement and email change

1. Open tenant detail `Admin Access` tab.
2. Replace main admin only with a deliberate chooser action.
3. For email change, split UX into request and confirm steps.
4. Do not collapse request and confirm into one submit because the backend requires both `requestId` and `verificationToken` at confirmation time.

## Changelog publish

1. Create or edit entries in `/platform/changelog`.
2. Validate semver before submit.
3. Use soft-delete from the changelog list, not from tenant detail.
