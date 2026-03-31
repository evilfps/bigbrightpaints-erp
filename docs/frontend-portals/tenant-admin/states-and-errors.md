# States And Errors

## Shell bootstrap states

- `bootstrapping`
- `must-change-password`
- `ready`
- `unauthenticated`
- `forbidden`
- `tenant-scope-mismatch`

Use `tenant-scope-mismatch` when the backend rejects `X-Company-Code` or a request is sent without the current tenant scope.

## User screen states

- `list-loading`
- `list-empty`
- `detail-loading`
- `saving`
- `action-pending`
- `save-failed`

Important user-management failures:

| Condition | Likely cause | UI treatment |
|---|---|---|
| duplicate email | email already exists in auth scope | inline email error |
| forbidden role assignment | attempted elevated or invalid role set | keep modal open and show validation message |
| quota hit | tenant cannot add more enabled users | show blocking banner on create form |
| masked missing user | target user is out of scope or deleted | show generic not-found state |

## Approval states

- `inbox-loading`
- `inbox-empty`
- `decision-pending`
- `decision-succeeded`
- `decision-failed`

Important approval notes:

- Reject should require a human-readable reason in the UI even though the API request body is optional.
- Sensitive requester fields may be absent. Render null-safe detail panels.

## Support ticket states

- `list-loading`
- `create-ready`
- `create-submitting`
- `detail-ready`
- `github-sync-warning`

Use `github-sync-warning` when `githubLastError` is present or `githubIssueState` is unexpected.
