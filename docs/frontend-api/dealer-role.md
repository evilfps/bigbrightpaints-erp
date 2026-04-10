# Dealer Role API Handoff

Role target: `ROLE_DEALER` (dealer portal only).

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Dealer access is constrained to own dashboard, orders (read-only), invoices, ledger, aging, support tickets, and credit-limit requests

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
| List own support tickets | `GET /api/v1/dealer-portal/support/tickets` | — | `ApiResponse<SupportTicketListResponse>` | `ROLE_DEALER` |
| Create support ticket | `POST /api/v1/dealer-portal/support/tickets` | `SupportTicketCreateRequest` | `ApiResponse<SupportTicketResponse>` | `ROLE_DEALER` |
| View own support ticket | `GET /api/v1/dealer-portal/support/tickets/{ticketId}` | — | `ApiResponse<SupportTicketResponse>` | `ROLE_DEALER` |

## DTOs used by dealer UI

- `DealerPortalCreditLimitRequestCreateRequest`
- `CreditLimitRequestDto`
- `SupportTicketCreateRequest`, `SupportTicketResponse`, `SupportTicketListResponse`
- Invoice, order, ledger, aging, and dashboard payloads are returned as typed maps; consult `openapi.json` schemas for field-level detail
