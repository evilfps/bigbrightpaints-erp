# curl probes (GET-only)

Conventions:
- These scripts are GET-only and should be safe against side effects.
- Expected env vars:
  - `BASE_URL` (e.g., `http://localhost:8080`)
  - `TOKEN` (JWT bearer)
  - `COMPANY_CODE` (for `X-Company-Id`)

