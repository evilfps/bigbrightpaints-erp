# Dealer Role API Handoff

Role target: `ROLE_DEALER` (dealer portal only).

Last reviewed: 2026-04-19

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Dealer access is constrained to own dashboard, orders (read-only), invoices, ledger, aging, credit-limit requests, and problem reporting

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
|---|---|---|---|---|
| Dealer dashboard | `GET /api/v1/dealer-portal/dashboard` | — | `ApiResponse<Map<String,Object>>` | `ROLE_DEALER` |
| List own orders | `GET /api/v1/dealer-portal/orders` | — | `ApiResponse<Map<String,Object>>` | `ROLE_DEALER` |
| View own invoices | `GET /api/v1/dealer-portal/invoices` | — | `ApiResponse<Map<String,Object>>` | `ROLE_DEALER` |
| Download own invoice PDF | `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf` | — | `application/pdf` | `ROLE_DEALER` |
| Own ledger | `GET /api/v1/dealer-portal/ledger` | — | `ApiResponse<Map<String,Object>>` | `ROLE_DEALER` |
| Own aging summary | `GET /api/v1/dealer-portal/aging` | — | `ApiResponse<Map<String,Object>>` | `ROLE_DEALER` |
| Submit credit-limit request | `POST /api/v1/dealer-portal/credit-limit-requests` | `DealerPortalCreditLimitRequestCreateRequest` | `ApiResponse<CreditLimitRequestDto>` | `ROLE_DEALER` |
| Report a problem | `POST /api/v1/incidents/report` | `IncidentReportCreateRequest` | `ApiResponse<IncidentReportResponse>` | authenticated |
| Changelog list | `GET /api/v1/changelog` | — | `ApiResponse<PageResponse<AppReleaseResponse>>` | authenticated |
| Latest release | `GET /api/v1/changelog/latest` | — | `ApiResponse<AppReleaseResponse>` | authenticated |
| Runtime version policy | `GET /api/v1/runtime/version?installedVersion=` | — | `ApiResponse<RuntimeVersionResponse>` | authenticated |

## DTOs used by dealer UI

- `DealerPortalCreditLimitRequestCreateRequest`
- `CreditLimitRequestDto`
- `IncidentReportCreateRequest`, `IncidentReportResponse`
- `AppReleaseResponse`, `RuntimeVersionResponse`
- Invoice, order, ledger, aging, and dashboard payloads are returned as typed maps; consult `openapi.json` schemas for field-level detail
