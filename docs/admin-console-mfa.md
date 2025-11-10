# Admin Console MFA Integration

The backend now enforces TOTP-based MFA whenever an account has enabled it. This guide explains how the React admin console (or any client) should interact with the new endpoints.

## Login Flow

1. **Primary credentials** – Submit `POST /api/v1/auth/login` with `{ email, password, companyCode }`.
2. **MFA required** – If the response is a 5xx error with message `MFA code required` or `Invalid MFA code`, prompt the user for either:
   - A 6‑digit code from their authenticator app (`mfaCode`), or
   - One of the recovery codes obtained during enrollment (`recoveryCode`).
3. **Resubmit** – Include the new field in the same login payload:

```json
{
  "email": "mfa-user@bbp.com",
  "password": "ChangeMe123!",
  "companyCode": "ACME",
  "mfaCode": "123456"
}
```

The response body still matches `AuthResponse` and can be stored in the same token cache.

## Determining Whether MFA Is Enabled

- `GET /api/v1/auth/me` now returns `mfaEnabled`. Frontend can toggle the UI (show “Enable MFA” or “Disable MFA”) based on this flag.
- During login failures, the backend does not leak whether MFA is enabled; instead rely on the 5xx error mentioned above to trigger the second step.

## Enrollment UI

Authenticated users can enable MFA from their profile/security settings:

1. **Start enrollment** – `POST /api/v1/auth/mfa/setup` (requires Bearer token). Response payload:

```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "qrUri": "otpauth://totp/BigBright%20ERP:admin@bbp.dev?...",
  "recoveryCodes": ["ABCD2345EF", "..."]
}
```

2. **Display options** – Show both the QR code (rendered from `qrUri`) and the plain secret as fallback. Persist the recovery codes securely (download, copy, or print).
3. **Activation** – Ask the user to enter the 6‑digit code from their authenticator. POST it to `/api/v1/auth/mfa/activate` with `{ "code": "123456" }`.
4. **Confirmation** – On success, update local state (`mfaEnabled = true`) and close the setup modal.

## Disabling MFA

To disable, prompt the user for either a fresh authenticator code or a recovery code and call:

```
POST /api/v1/auth/mfa/disable
Authorization: Bearer <token>
{
  "code": "654321"
}
```

If the user lost their device but still has a recovery code, send it as `recoveryCode` instead. Recovery codes are one-time—after use they are invalidated server-side, so warn users accordingly.

## Handling Recovery Codes During Login

Recovery codes are meant for emergency access:

- Add a “Use recovery code” action on the MFA prompt and submit the login payload with `recoveryCode`.
- The backend consumes the code and invalidates it; the UI should warn users before submission and prompt them to generate new codes (by re-running the setup flow) once they regain access.

## Error Handling Cheatsheet

| Scenario                            | HTTP Status | Message                         | Client Action                                  |
|------------------------------------|-------------|---------------------------------|------------------------------------------------|
| MFA required (code missing)        | 500         | `MFA code required`             | Show MFA prompt                                |
| Invalid code/recovery code         | 500         | `Invalid MFA code`              | Display error, allow retry                     |
| Activation with wrong code         | 400         | Validation error (bad payload)  | Highlight input                                |
| Disable missing code & recovery    | 400         | `Provide either code or ...`    | Ask user to supply one of the values           |

## Example React Hooks Skeleton

```ts
async function login(payload: LoginPayload): Promise<AuthResponse> {
  try {
    const { data } = await api.post<AuthResponse>("/api/v1/auth/login", payload);
    return data;
  } catch (error) {
    if (isMfaError(error)) {
      throw new RequiresMfaError();
    }
    throw error;
  }
}

async function startMfa() {
  const { data } = await api.post<ApiResponse<MfaSetupResponse>>("/api/v1/auth/mfa/setup");
  return data.data;
}
```

Use this as a blueprint for wiring the actual admin console.
