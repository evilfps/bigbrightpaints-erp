-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Show GST input/output/payable account configuration for the company.

SELECT
  id AS company_id,
  code AS company_code,
  gst_input_tax_account_id,
  gst_output_tax_account_id,
  gst_payable_account_id
FROM companies
WHERE id = :company_id;
