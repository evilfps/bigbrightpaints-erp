-- CODE-RED pre-deploy scans (READ-ONLY)
-- Run against the target database BEFORE deploying.
-- Gate: violations must be investigated and resolved before deploy.

-- 1) Journal uniqueness violations (should be impossible if the constraint exists everywhere)
select
  company_id,
  reference_number,
  count(*) as cnt
from journal_entries
group by company_id, reference_number
having count(*) > 1
order by cnt desc, company_id, reference_number;

-- 2) Dispatched packaging slips missing required links (invoice + AR journal + COGS journal)
select
  company_id,
  id as packaging_slip_id,
  slip_number,
  status,
  invoice_id,
  journal_entry_id,
  cogs_journal_entry_id,
  confirmed_at,
  dispatched_at
from packaging_slips
where upper(status) = 'DISPATCHED'
  and (
    invoice_id is null
    or journal_entry_id is null
    or cogs_journal_entry_id is null
  )
order by company_id, packaging_slip_id;

-- 2b) Duplicate journal links across packaging slips (violates slip-level idempotency)
select
  company_id,
  journal_entry_id,
  count(*) as cnt
from packaging_slips
where journal_entry_id is not null
group by company_id, journal_entry_id
having count(*) > 1
order by cnt desc, company_id, journal_entry_id;

select
  company_id,
  cogs_journal_entry_id,
  count(*) as cnt
from packaging_slips
where cogs_journal_entry_id is not null
group by company_id, cogs_journal_entry_id
having count(*) > 1
order by cnt desc, company_id, cogs_journal_entry_id;

-- 3) Invoices missing journal entry
select
  company_id,
  id as invoice_id,
  invoice_number,
  status,
  sales_order_id,
  journal_entry_id,
  issue_date
from invoices
where journal_entry_id is null
order by company_id, invoice_id;

-- 4) Packaging slips pointing to missing invoices (or cross-company mismatch)
select
  p.company_id,
  p.id as packaging_slip_id,
  p.slip_number,
  p.invoice_id
from packaging_slips p
left join invoices i
  on i.id = p.invoice_id
where p.invoice_id is not null
  and (i.id is null or i.company_id <> p.company_id)
order by p.company_id, p.id;

-- 5) Payroll runs missing canonical fields (schema drift / legacy runs)
select
  company_id,
  id as payroll_run_id,
  run_number,
  run_type,
  period_start,
  period_end,
  status,
  idempotency_key,
  journal_entry_id,
  created_at
from payroll_runs
where run_number is null
   or run_type is null
   or period_start is null
   or period_end is null
order by company_id, payroll_run_id;

-- 6) Goods receipts without supplier invoices (uninvoiced GRNs)
select
  gr.company_id,
  gr.id as goods_receipt_id,
  gr.receipt_number,
  gr.receipt_date,
  gr.status,
  gr.supplier_id,
  gr.purchase_order_id
from goods_receipts gr
where coalesce(upper(gr.status), '') <> 'INVOICED'
order by gr.company_id, gr.receipt_date, gr.id;

-- 7) Orders with multiple invoices where fulfillment marker is not deterministic (ambiguous backfill risk)
with per_order as (
  select
    so.company_id,
    so.id as sales_order_id,
    so.order_number,
    so.fulfillment_invoice_id,
    count(i.id) as invoice_count,
    max(i.id) as max_invoice_id
  from sales_orders so
  join invoices i
    on i.sales_order_id = so.id
  group by so.company_id, so.id, so.order_number, so.fulfillment_invoice_id
)
select *
from per_order
where invoice_count > 1
  and fulfillment_invoice_id is not null
  and fulfillment_invoice_id <> max_invoice_id
order by company_id, sales_order_id;
