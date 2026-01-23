-- Ensure GST tax accounts and company default accounts are configured consistently.
-- Prefer canonical codes (GST-IN/GST-OUT/INV/COGS/REV/DISC) and fall back to numeric
-- codes where they already exist (1700/2100/1200/5000/4000).

-- GST input tax (ASSET)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'GST-IN', 'GST Input Tax', 'ASSET', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-IN', '1700')
);

-- GST output tax (LIABILITY)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'GST-OUT', 'GST Output Tax', 'LIABILITY', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-OUT', '2100')
);

-- GST payable (LIABILITY)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'GST-PAY', 'GST Payable', 'LIABILITY', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-PAY', '2200')
);

-- Inventory (ASSET)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'INV', 'Inventory', 'ASSET', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('INV', '1200')
);

-- COGS (COGS)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'COGS', 'Cost of Goods Sold', 'COGS', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('COGS', '5000')
);

-- Revenue (REVENUE)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'REV', 'Revenue', 'REVENUE', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('REV', '4000')
);

-- Discounts (EXPENSE)
INSERT INTO accounts (company_id, code, name, type, balance)
SELECT c.id, 'DISC', 'Discounts', 'EXPENSE', 0
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) = 'DISC'
);

-- Align company GST account IDs to canonical GST accounts.
UPDATE companies c
SET gst_input_tax_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-IN', '1700')
    ORDER BY CASE UPPER(a.code) WHEN 'GST-IN' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.gst_input_tax_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.gst_input_tax_account_id AND UPPER(a.code) IN ('GST-IN', '1700')
   );

UPDATE companies c
SET gst_output_tax_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-OUT', '2100')
    ORDER BY CASE UPPER(a.code) WHEN 'GST-OUT' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.gst_output_tax_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.gst_output_tax_account_id AND UPPER(a.code) IN ('GST-OUT', '2100')
   );

UPDATE companies c
SET gst_payable_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-PAY', '2200')
    ORDER BY CASE UPPER(a.code) WHEN 'GST-PAY' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.gst_payable_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.gst_payable_account_id AND UPPER(a.code) IN ('GST-PAY', '2200')
   );

-- Align company default account IDs to canonical chart codes.
UPDATE companies c
SET default_inventory_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('INV', '1200')
    ORDER BY CASE UPPER(a.code) WHEN 'INV' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.default_inventory_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.default_inventory_account_id AND UPPER(a.code) IN ('INV', '1200')
   );

UPDATE companies c
SET default_cogs_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('COGS', '5000')
    ORDER BY CASE UPPER(a.code) WHEN 'COGS' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.default_cogs_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.default_cogs_account_id AND UPPER(a.code) IN ('COGS', '5000')
   );

UPDATE companies c
SET default_revenue_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('REV', '4000')
    ORDER BY CASE UPPER(a.code) WHEN 'REV' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.default_revenue_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.default_revenue_account_id AND UPPER(a.code) IN ('REV', '4000')
   );

UPDATE companies c
SET default_discount_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) = 'DISC'
    ORDER BY a.id
    LIMIT 1
)
WHERE c.default_discount_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.default_discount_account_id AND UPPER(a.code) = 'DISC'
   );

UPDATE companies c
SET default_tax_account_id = (
    SELECT a.id
    FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) IN ('GST-OUT', '2100')
    ORDER BY CASE UPPER(a.code) WHEN 'GST-OUT' THEN 1 ELSE 2 END, a.id
    LIMIT 1
)
WHERE c.default_tax_account_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM accounts a
       WHERE a.id = c.default_tax_account_id AND UPPER(a.code) IN ('GST-OUT', '2100')
   );
