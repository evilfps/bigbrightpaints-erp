-- List companies to obtain numeric company IDs for other queries.
SELECT
  id,
  code,
  name,
  timezone,
  base_currency,
  default_inventory_account_id
FROM companies
ORDER BY id;

