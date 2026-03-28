UPDATE public.companies
SET gst_input_tax_account_id = NULL,
    gst_output_tax_account_id = NULL,
    gst_payable_account_id = NULL
WHERE COALESCE(default_gst_rate, 18) = 0;

WITH resolved_gst_accounts AS (
    SELECT
        c.id AS company_id,
        COALESCE(
            c.gst_input_tax_account_id,
            (
                SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) IN ('GST-IN', 'TDS-RECEIVABLE')
                ORDER BY
                    CASE upper(a.code)
                        WHEN 'GST-IN' THEN 0
                        ELSE 1
                    END,
                    a.id
                LIMIT 1
            )
        ) AS resolved_gst_input_tax_account_id,
        COALESCE(
            c.gst_output_tax_account_id,
            (
                SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) IN ('GST-OUT', 'TAX-PAYABLE')
                ORDER BY
                    CASE upper(a.code)
                        WHEN 'GST-OUT' THEN 0
                        ELSE 1
                    END,
                    a.id
                LIMIT 1
            ),
            c.default_tax_account_id
        ) AS resolved_gst_output_tax_account_id,
        COALESCE(
            c.gst_payable_account_id,
            (
                SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) IN ('GST-PAY', 'TDS-PAYABLE', 'TAX-PAYABLE')
                ORDER BY
                    CASE upper(a.code)
                        WHEN 'GST-PAY' THEN 0
                        WHEN 'TDS-PAYABLE' THEN 1
                        ELSE 2
                    END,
                    a.id
                LIMIT 1
            ),
            c.default_tax_account_id,
            c.gst_output_tax_account_id,
            (
                SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) IN ('GST-OUT', 'TAX-PAYABLE')
                ORDER BY
                    CASE upper(a.code)
                        WHEN 'GST-OUT' THEN 0
                        ELSE 1
                    END,
                    a.id
                LIMIT 1
            )
        ) AS resolved_gst_payable_account_id
    FROM public.companies c
    WHERE COALESCE(c.default_gst_rate, 18) <> 0
)
UPDATE public.companies c
SET gst_input_tax_account_id = resolved.resolved_gst_input_tax_account_id,
    gst_output_tax_account_id = resolved.resolved_gst_output_tax_account_id,
    gst_payable_account_id = resolved.resolved_gst_payable_account_id
FROM resolved_gst_accounts resolved
WHERE c.id = resolved.company_id
  AND (
      c.gst_input_tax_account_id IS DISTINCT FROM resolved.resolved_gst_input_tax_account_id
      OR c.gst_output_tax_account_id IS DISTINCT FROM resolved.resolved_gst_output_tax_account_id
      OR c.gst_payable_account_id IS DISTINCT FROM resolved.resolved_gst_payable_account_id
  );
