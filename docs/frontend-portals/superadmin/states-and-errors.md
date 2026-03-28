# States And Errors

## Screen states

### Tenant onboarding

- `idle`
- `loading-templates`
- `ready`
- `submitting`
- `bootstrap-confirmed`
- `bootstrap-warning`
- `failed`

Use `bootstrap-warning` when the request returns 200 but one or more bootstrap truth flags are false.

### Tenant detail

- `loading`
- `ready`
- `mutating-lifecycle`
- `mutating-limits`
- `mutating-modules`
- `mutating-support`
- `mutating-admin-access`
- `reload-failed`

## Important backend failures to surface clearly

| Condition | Likely cause | UI treatment |
|---|---|---|
| duplicate tenant code | `code` already exists | inline field error on tenant code |
| duplicate admin email | email already exists in auth scope | inline error on first-admin email |
| unsupported `coaTemplateCode` | stale template selection | reload templates and require reselection |
| invalid lifecycle transition | illegal state or missing reason | keep modal open and show returned message |
| admin email change confirm failure | wrong `requestId` or verification token | keep confirmation form visible, do not discard request state |
| force logout failure | tenant lookup or session revocation issue | preserve tenant detail and show retry banner |

## Empty states

- Tenant list empty: show onboarding CTA.
- Support timeline empty: show "No support actions recorded yet."
- Changelog empty: show create-entry CTA.
