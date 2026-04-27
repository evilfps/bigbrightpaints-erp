ALTER TABLE public.raw_material_purchases
    ADD COLUMN IF NOT EXISTS due_date DATE;

UPDATE public.raw_material_purchases p
SET due_date = CASE
                   WHEN p.invoice_date IS NULL THEN NULL
                   WHEN s.payment_terms = 'NET_60' THEN (p.invoice_date + INTERVAL '60 day')::date
                   WHEN s.payment_terms = 'NET_90' THEN (p.invoice_date + INTERVAL '90 day')::date
                   WHEN s.payment_terms = 'NET_30' OR s.payment_terms IS NULL THEN (p.invoice_date + INTERVAL '30 day')::date
                   ELSE NULL
    END
FROM public.suppliers s
WHERE p.company_id = s.company_id
  AND p.supplier_id = s.id
  AND p.due_date IS NULL;

CREATE INDEX IF NOT EXISTS idx_raw_material_purchases_supplier_due_invoice
    ON public.raw_material_purchases USING btree (company_id, supplier_id, due_date, invoice_date, id);
