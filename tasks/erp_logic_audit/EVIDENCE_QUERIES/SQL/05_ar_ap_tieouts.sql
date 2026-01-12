-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Tie-out GL control balances vs subledger totals (using as-built sign conventions in code).
-- Reference:
--   `ReconciliationService` in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java`

WITH ar_accounts AS (
  SELECT a.id, a.code, a.balance
  FROM accounts a
  WHERE a.company_id = :company_id
    AND a.type = 'ASSET'
    AND a.code IS NOT NULL
    AND (
      UPPER(a.code) LIKE '%AR%'
      OR UPPER(a.code) LIKE '%RECEIVABLE%'
    )
),
ap_accounts AS (
  SELECT a.id, a.code, a.balance
  FROM accounts a
  WHERE a.company_id = :company_id
    AND a.type = 'LIABILITY'
    AND a.code IS NOT NULL
    AND (
      UPPER(a.code) LIKE '%AP%'
      OR UPPER(a.code) LIKE '%PAYABLE%'
    )
),
dealer_ledger AS (
  SELECT COALESCE(SUM(dle.debit - dle.credit), 0) AS total
  FROM dealer_ledger_entries dle
  WHERE dle.company_id = :company_id
),
supplier_ledger AS (
  SELECT COALESCE(SUM(sle.credit - sle.debit), 0) AS total
  FROM supplier_ledger_entries sle
  WHERE sle.company_id = :company_id
)
SELECT
  (SELECT COALESCE(SUM(balance), 0) FROM ar_accounts) AS gl_ar_balance,
  (SELECT total FROM dealer_ledger) AS dealer_ledger_total,
  (SELECT COALESCE(SUM(balance), 0) FROM ar_accounts) - (SELECT total FROM dealer_ledger) AS ar_variance,
  (SELECT COALESCE(SUM(balance), 0) FROM ap_accounts) AS gl_ap_balance,
  (SELECT total FROM supplier_ledger) AS supplier_ledger_total,
  (SELECT COALESCE(SUM(balance), 0) FROM ap_accounts) - (SELECT total FROM supplier_ledger) AS ap_variance;

