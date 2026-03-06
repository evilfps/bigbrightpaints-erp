# Forgot-password persistence failure regression fix

- Feature: `forgot-password-persistence-failure-regression-fix`
- Frontend impact: review only

## Notes

- `POST /api/v1/auth/password/forgot` keeps the same request body and the same generic success payload for unknown-user, disabled-user, and delivery/configuration-masked cases.
- When reset-token persistence fails before a reset link can be stored, the endpoint now returns a controlled non-success `ApiResponse` instead of the normal generic success contract.
- Frontend code does not need a payload-shape migration, but any UX that assumed forgot-password always returns `200 OK` should tolerate a controlled temporary-failure response.
