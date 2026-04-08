# Sales Role API Handoff

Role target: `ROLE_SALES` (admin approvals remain `ROLE_ADMIN`).

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Sales must not call accounting mutation routes or `POST /api/v1/dispatch/confirm`

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
|---|---|---|---|---|
| Dashboard | `GET /api/v1/sales/dashboard` | — | `ApiResponse<SalesDashboardDto>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Dealer create | `POST /api/v1/dealers` | `CreateDealerRequest` | `ApiResponse<DealerResponse>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Dealer detail | `GET /api/v1/dealers/{dealerId}` | — | `ApiResponse<DealerResponse>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Dealer update | `PUT /api/v1/dealers/{dealerId}` | `CreateDealerRequest` | `ApiResponse<DealerResponse>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Dealer dunning hold | `POST /api/v1/dealers/{dealerId}/dunning/hold` | — | `ApiResponse<DealerDunningHoldResponse>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Order create | `POST /api/v1/sales/orders` | `SalesOrderRequest` | `ApiResponse<SalesOrderDto>` (`201`/`200`) | `ROLE_SALES` or `ROLE_ADMIN` |
| Order update | `PUT /api/v1/sales/orders/{id}` | `SalesOrderRequest` | `ApiResponse<SalesOrderDto>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Order confirm | `POST /api/v1/sales/orders/{id}/confirm` | — | `ApiResponse<SalesOrderDto>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Order cancel | `POST /api/v1/sales/orders/{id}/cancel` | `CancelRequest` | `ApiResponse<SalesOrderDto>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Order timeline | `GET /api/v1/sales/orders/{id}/timeline` | — | `ApiResponse<List<SalesOrderStatusHistoryDto>>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Promotion create | `POST /api/v1/sales/promotions` | `PromotionRequest` | `ApiResponse<PromotionDto>` | `ROLE_SALES` or `ROLE_ADMIN` |
| Credit override request | `POST /api/v1/credit/override-requests` | `CreditLimitOverrideRequestCreateRequest` | `ApiResponse<CreditLimitOverrideRequestDto>` (`201`) | `ROLE_SALES` or `ROLE_FACTORY` or `ROLE_ADMIN` |
| Credit override approve | `POST /api/v1/credit/override-requests/{id}/approve` | — | `ApiResponse<CreditLimitOverrideRequestDto>` | `ROLE_ADMIN` |
| Credit override reject | `POST /api/v1/credit/override-requests/{id}/reject` | — | `ApiResponse<CreditLimitOverrideRequestDto>` | `ROLE_ADMIN` |

## DTOs used by sales UI

- `SalesDashboardDto`
- `CreateDealerRequest`, `DealerResponse`, `DealerLookupResponse`, `DealerDunningHoldResponse`
- `SalesOrderRequest`, `SalesOrderDto`, `SalesOrderStatusHistoryDto`, `CancelRequest`
- `PromotionRequest`, `PromotionDto`
- `CreditLimitOverrideRequestCreateRequest`, `CreditLimitOverrideRequestDto`
- `CreditLimitRequestCreateRequest`, `CreditLimitRequestDto`
