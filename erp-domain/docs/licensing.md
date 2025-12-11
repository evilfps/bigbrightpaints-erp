# CryptoLens licensing

The service now exposes `erp.licensing` settings so we can bind a CryptoLens license without hard-coding secrets in the repo.

License metadata provided:
- Key name: `ORCHESTRATOR`
- Algorithm: `SKM15`
- Product Id: `31720`
- Description: `ERP`
- Created: `2025-12-08`

Configuration (preferred via environment variables):
- `ERP_LICENSE_KEY` - set to your CryptoLens license key (e.g., `ORCHESTRATOR`).
- `ERP_LICENSE_PRODUCT_ID` - defaults to `31720`.
- `ERP_LICENSE_ALGORITHM` - defaults to `SKM15`.
- `ERP_LICENSE_CREATED` - defaults to `2025-12-08`.
- `ERP_LICENSE_DESCRIPTION` - defaults to `ERP`.
- `ERP_LICENSE_ENFORCE` - `true` to fail startup if no key is set (already on in `application-prod.yml`).
- `ERP_LICENSE_ACCESS_TOKEN` - optional CryptoLens access token if you want to call the activation API later.

Quick setup (WSL/bash):
```bash
export ERP_LICENSE_KEY="ORCHESTRATOR"
export ERP_LICENSE_PRODUCT_ID=31720
export ERP_LICENSE_ENFORCE=true
# optional when you want to call CryptoLens activation API
# export ERP_LICENSE_ACCESS_TOKEN="SKMAPI_..."
```

PowerShell:
```powershell
$env:ERP_LICENSE_KEY="ORCHESTRATOR"
$env:ERP_LICENSE_PRODUCT_ID="31720"
$env:ERP_LICENSE_ENFORCE="true"
```

Notes:
- `application.yml` carries the metadata, but the actual key is meant to come from env/secret storage so we can share the code safely.
- The `LicensingGuard` runs on startup; it only blocks when `erp.licensing.enforce=true` (dev/test can remain false).
- If you add an access token later, we can wire an online activation call to CryptoLens using the stored product id and key.
