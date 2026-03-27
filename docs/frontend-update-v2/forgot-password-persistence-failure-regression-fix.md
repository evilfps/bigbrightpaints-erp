# Forgot-password persistence failure regression fix

- Feature: `forgot-password-persistence-failure-regression-fix`
- Frontend impact: review only

## Notes

- `POST /api/v1/auth/password/forgot` keeps the same request body and generic success payload only for masked unknown-user and disabled-user cases.
- For a known scoped account, reset-token persistence failures, disabled reset-email configuration, and SMTP delivery failures now fail closed instead of returning the generic success contract.
- Frontend code does not need a payload-shape migration, but any UX that assumed forgot-password always returns `200 OK` must now treat controlled recovery failures as retryable or support-routed errors.
