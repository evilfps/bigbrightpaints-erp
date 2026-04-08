# Dealer Role API Handoff

Role target: `ROLE_DEALER` (dealer portal only).

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Dealer access is constrained to own profile, own orders, own invoices, own exports

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
|---|---|---|---|---|
| Dealer profile | `GET /api/v1/dealer-portal/profile` | — | `ApiResponse<DealerPortalProfileDto>` | `ROLE_DEALER` |
| Dealer dashboard | `GET /api/v1/dealer-portal/dashboard` | — | `ApiResponse<DealerPortalDashboardDto>` | `ROLE_DEALER` |
| List own orders | `GET /api/v1/dealer-portal/orders` | — | `ApiResponse<List<SalesOrderDto>>` | `ROLE_DEALER` |
| Create own order | `POST /api/v1/dealer-portal/orders` | `SalesOrderRequest` | `ApiResponse<SalesOrderDto>` | `ROLE_DEALER` |
| View own order | `GET /api/v1/dealer-portal/orders/{orderId}` | — | `ApiResponse<SalesOrderDto>` | `ROLE_DEALER` |
| Edit own order | `PUT /api/v1/dealer-portal/orders/{orderId}` | `SalesOrderRequest` | `ApiResponse<SalesOrderDto>` | `ROLE_DEALER` |
| Confirm own order | `POST /api/v1/dealer-portal/orders/{orderId}/confirm` | — | `ApiResponse<SalesOrderDto>` | `ROLE_DEALER` |
| Cancel own order | `POST /api/v1/dealer-portal/orders/{orderId}/cancel` | `CancelRequest` | `ApiResponse<SalesOrderDto>` | `ROLE_DEALER` |
| View own invoices | `GET /api/v1/dealer-portal/invoices` | — | `ApiResponse<List<InvoiceDto>>` | `ROLE_DEALER` |
| Request export | `POST /api/v1/dealer-portal/exports` | `CreateExportRequestRequest` | `ApiResponse<ExportRequestDto>` | `ROLE_DEALER` |
| View export requests | `GET /api/v1/dealer-portal/exports` | — | `ApiResponse<List<ExportRequestDto>>` | `ROLE_DEALER` |

## DTOs used by dealer UI

- `DealerPortalProfileDto`, `DealerPortalDashboardDto`
- `SalesOrderRequest`, `SalesOrderDto`, `CancelRequest`
- `InvoiceDto`
- `CreateExportRequestRequest`, `ExportRequestDto`
