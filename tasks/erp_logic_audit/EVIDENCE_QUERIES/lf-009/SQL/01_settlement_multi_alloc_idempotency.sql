-- Purpose:
--   Show multi-allocation settlements can share the same idempotency key.
-- Notes:
--   Seeds a company/dealer, two invoices, one journal entry, and two allocations with the same key.

WITH company AS (
    INSERT INTO companies (name, code)
    VALUES ('LF-009 Ltd', 'LF-009')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
dealer AS (
    INSERT INTO dealers (company_id, name, code)
    SELECT id, 'LF-009 Dealer', 'LF-009-DEALER'
    FROM company
    ON CONFLICT (company_id, code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
journal AS (
    INSERT INTO journal_entries (company_id, reference_number, entry_date, status)
    SELECT company.id,
           'LF-009-SETTLE-' || substring(md5(random()::text) for 6),
           CURRENT_DATE,
           'POSTED'
    FROM company
    RETURNING id
),
invoice_one AS (
    INSERT INTO invoices (company_id, dealer_id, invoice_number, status, subtotal, tax_total, total_amount, currency, issue_date, outstanding_amount)
    SELECT company.id,
           dealer.id,
           'LF-009-INV-1-' || substring(md5(random()::text) for 6),
           'ISSUED',
           100,
           0,
           100,
           'INR',
           CURRENT_DATE,
           100
    FROM company, dealer
    RETURNING id
),
invoice_two AS (
    INSERT INTO invoices (company_id, dealer_id, invoice_number, status, subtotal, tax_total, total_amount, currency, issue_date, outstanding_amount)
    SELECT company.id,
           dealer.id,
           'LF-009-INV-2-' || substring(md5(random()::text) for 6),
           'ISSUED',
           200,
           0,
           200,
           'INR',
           CURRENT_DATE,
           200
    FROM company, dealer
    RETURNING id
),
allocation_one AS (
    INSERT INTO partner_settlement_allocations (
        company_id,
        partner_type,
        dealer_id,
        invoice_id,
        journal_entry_id,
        settlement_date,
        allocation_amount,
        discount_amount,
        write_off_amount,
        fx_difference_amount,
        currency,
        idempotency_key
    )
    SELECT company.id,
           'DEALER',
           dealer.id,
           invoice_one.id,
           journal.id,
           CURRENT_DATE,
           60,
           0,
           0,
           0,
           'INR',
           'LF-009-IDEMP'
    FROM company, dealer, invoice_one, journal
    RETURNING id
),
allocation_two AS (
    INSERT INTO partner_settlement_allocations (
        company_id,
        partner_type,
        dealer_id,
        invoice_id,
        journal_entry_id,
        settlement_date,
        allocation_amount,
        discount_amount,
        write_off_amount,
        fx_difference_amount,
        currency,
        idempotency_key
    )
    SELECT company.id,
           'DEALER',
           dealer.id,
           invoice_two.id,
           journal.id,
           CURRENT_DATE,
           140,
           0,
           0,
           0,
           'INR',
           'LF-009-IDEMP'
    FROM company, dealer, invoice_two, journal
    RETURNING id
)
SELECT id FROM allocation_one
UNION ALL
SELECT id FROM allocation_two;

SELECT company_id,
       id,
       invoice_id,
       idempotency_key
FROM partner_settlement_allocations
WHERE company_id = (SELECT id FROM companies WHERE code = 'LF-009')
  AND idempotency_key = 'LF-009-IDEMP'
ORDER BY id;
