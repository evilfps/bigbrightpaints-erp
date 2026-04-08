# Accounting Role API Handoff

Role target: `ROLE_ACCOUNTING` (plus `ROLE_ADMIN` where explicitly noted).

## Auth requirements

- Bearer JWT from `POST /api/v1/auth/login`
- Tenant-scoped requests must include `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Supplier PDF exports are admin-only (`ROLE_ADMIN`)

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
|---|---|---|---|---|
| Chart of accounts | `GET /api/v1/accounting/accounts` | — | `ApiResponse<List<AccountDto>>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Chart of accounts | `POST /api/v1/accounting/accounts` | `AccountRequest` | `ApiResponse<AccountDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Default accounts | `GET /api/v1/accounting/default-accounts` | — | `ApiResponse<CompanyDefaultAccountsResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Default accounts | `PUT /api/v1/accounting/default-accounts` | `CompanyDefaultAccountsRequest` | `ApiResponse<CompanyDefaultAccountsResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Manual journal | `POST /api/v1/accounting/journal-entries` | `JournalEntryRequest` | `ApiResponse<JournalEntryDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Journal reversal | `POST /api/v1/accounting/journal-entries/{entryId}/reverse` | `JournalEntryReversalRequest` | `ApiResponse<JournalEntryDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Period close request | `POST /api/v1/accounting/periods/{periodId}/request-close` | `PeriodCloseRequestActionRequest` | `ApiResponse<PeriodCloseRequestDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Reconciliation session | `POST /api/v1/accounting/reconciliation/bank/sessions` | `BankReconciliationSessionCreateRequest` | `ApiResponse<BankReconciliationSessionSummaryDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Reconciliation session items | `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` | `BankReconciliationSessionItemsUpdateRequest` | `ApiResponse<BankReconciliationSessionDetailDto>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Dealer settlement | `POST /api/v1/accounting/settlements/dealers` | `PartnerSettlementRequest` | `ApiResponse<PartnerSettlementResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Supplier settlement | `POST /api/v1/accounting/settlements/suppliers` | `PartnerSettlementRequest` | `ApiResponse<PartnerSettlementResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Supplier statement | `GET /api/v1/accounting/statements/suppliers/{supplierId}` | — | `ApiResponse<PartnerStatementResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Supplier aging | `GET /api/v1/accounting/aging/suppliers/{supplierId}` | — | `ApiResponse<AgingSummaryResponse>` | `ROLE_ACCOUNTING` or `ROLE_ADMIN` |
| Supplier statement PDF | `GET /api/v1/accounting/statements/suppliers/{supplierId}/pdf` | — | `application/pdf` | `ROLE_ADMIN` |
| Supplier aging PDF | `GET /api/v1/accounting/aging/suppliers/{supplierId}/pdf` | — | `application/pdf` | `ROLE_ADMIN` |

## DTOs used by accounting UI

- `AccountRequest`, `AccountDto`, `AccountNode`
- `CompanyDefaultAccountsRequest`, `CompanyDefaultAccountsResponse`
- `JournalEntryRequest`, `JournalEntryReversalRequest`, `JournalEntryDto`
- `AccountingPeriodRequest`, `AccountingPeriodDto`, `PeriodCloseRequestDto`, `PeriodCloseRequestActionRequest`
- `BankReconciliationSessionCreateRequest`, `BankReconciliationSessionItemsUpdateRequest`, `BankReconciliationSessionSummaryDto`, `BankReconciliationSessionDetailDto`
- `PartnerSettlementRequest`, `PartnerSettlementResponse`
- `PartnerStatementResponse`, `AgingSummaryResponse`
