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

-- 3b) Posted-ish purchases missing journal entry (status alone is not proof)
select
  company_id,
  id as purchase_id,
  invoice_number,
  status,
  journal_entry_id,
  invoice_date
from raw_material_purchases
where upper(status) in ('POSTED', 'PARTIAL', 'PAID')
  and journal_entry_id is null
order by company_id, purchase_id;

-- 3c) Duplicate supplier allocations within a single settlement journal
select
  company_id,
  purchase_id,
  journal_entry_id,
  count(*) as cnt
from partner_settlement_allocations
where partner_type = 'SUPPLIER'
  and purchase_id is not null
  and journal_entry_id is not null
group by company_id, purchase_id, journal_entry_id
having count(*) > 1
order by cnt desc, company_id, purchase_id, journal_entry_id;

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

-- 6) Payroll runs duplicated by company + run_type + period (should be zero)
select
  company_id,
  run_type,
  period_start,
  period_end,
  count(*) as run_count,
  array_agg(id order by id) as run_ids
from payroll_runs
where run_type is not null
  and period_start is not null
  and period_end is not null
group by company_id, run_type, period_start, period_end
having count(*) > 1
order by company_id, run_type, period_start, period_end;

-- 7) Goods receipts without supplier invoices (uninvoiced GRNs)
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

-- 8) Orders with multiple invoices where fulfillment marker is not deterministic (ambiguous backfill risk)
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

-- 8) Inventory adjustments posted on a different date than the adjustment date
select
  ia.company_id,
  ia.id as adjustment_id,
  ia.reference_number,
  ia.adjustment_date,
  je.entry_date,
  ia.journal_entry_id
from inventory_adjustments ia
join journal_entries je
  on je.id = ia.journal_entry_id
where ia.journal_entry_id is not null
  and ia.adjustment_date <> je.entry_date
order by ia.company_id, ia.id;

-- 9) Closed periods missing snapshots (NO-GO)
select
  p.company_id,
  p.id as accounting_period_id,
  p.year,
  p.month,
  p.start_date,
  p.end_date,
  p.closed_at
from accounting_periods p
left join accounting_period_snapshots s
  on s.accounting_period_id = p.id
 and s.company_id = p.company_id
where p.status = 'CLOSED'
  and s.id is null
order by p.company_id, p.end_date, p.id;

-- 10) Posted journals dated inside CLOSED periods after the period was closed (NO-GO)
select
  p.company_id,
  p.id as accounting_period_id,
  p.start_date,
  p.end_date,
  p.closed_at,
  je.id as journal_entry_id,
  je.reference_number,
  je.entry_date,
  je.posted_at,
  je.created_at
from accounting_periods p
join journal_entries je
  on je.company_id = p.company_id
 and je.status = 'POSTED'
 and je.entry_date between p.start_date and p.end_date
where p.status = 'CLOSED'
  and p.closed_at is not null
  and coalesce(je.posted_at, je.created_at) > (p.closed_at at time zone 'UTC')
order by p.company_id, p.end_date, je.id;

-- 11) Closed-period drift: snapshot lines differ from as-of journal balances (NO-GO)
with snapshot_periods as (
  select p.id, p.company_id, p.end_date
  from accounting_periods p
  join accounting_period_snapshots s
    on s.accounting_period_id = p.id
   and s.company_id = p.company_id
  where p.status = 'CLOSED'
),
balances as (
  select
    sp.id as period_id,
    sp.company_id as company_id,
    jl.account_id as account_id,
    coalesce(sum(jl.debit), 0) as debit_sum,
    coalesce(sum(jl.credit), 0) as credit_sum
  from snapshot_periods sp
  join journal_entries je
    on je.company_id = sp.company_id
   and je.status = 'POSTED'
   and je.entry_date <= sp.end_date
  join journal_lines jl
    on jl.journal_entry_id = je.id
  group by sp.id, sp.company_id, jl.account_id
),
expected as (
  select
    period_id,
    company_id,
    account_id,
    case
      when (debit_sum - credit_sum) >= 0
        then (debit_sum - credit_sum)
      else 0
    end as expected_debit,
    case
      when (debit_sum - credit_sum) < 0
        then (credit_sum - debit_sum)
      else 0
    end as expected_credit
  from balances
),
snapshot_lines as (
  select
    s.accounting_period_id as period_id,
    s.company_id,
    l.account_id,
    coalesce(l.debit, 0) as snap_debit,
    coalesce(l.credit, 0) as snap_credit
  from accounting_period_snapshots s
  join accounting_period_trial_balance_lines l
    on l.snapshot_id = s.id
  join snapshot_periods sp
    on sp.id = s.accounting_period_id
   and sp.company_id = s.company_id
)
select
  coalesce(sl.company_id, e.company_id) as company_id,
  coalesce(sl.period_id, e.period_id) as accounting_period_id,
  coalesce(sl.account_id, e.account_id) as account_id,
  coalesce(sl.snap_debit, 0) as snap_debit,
  coalesce(sl.snap_credit, 0) as snap_credit,
  coalesce(e.expected_debit, 0) as expected_debit,
  coalesce(e.expected_credit, 0) as expected_credit
from expected e
full join snapshot_lines sl
  on sl.period_id = e.period_id
 and sl.company_id = e.company_id
 and sl.account_id = e.account_id
where abs(coalesce(e.expected_debit, 0) - coalesce(sl.snap_debit, 0)) > 0.01
   or abs(coalesce(e.expected_credit, 0) - coalesce(sl.snap_credit, 0)) > 0.01
order by company_id, accounting_period_id, account_id;

-- 11b) Closed-period snapshot totals mismatch (NO-GO)
with snapshot_totals as (
  select
    s.id as snapshot_id,
    s.company_id,
    s.accounting_period_id,
    coalesce(sum(l.debit), 0) as line_debit,
    coalesce(sum(l.credit), 0) as line_credit
  from accounting_period_snapshots s
  join accounting_periods p
    on p.id = s.accounting_period_id
   and p.company_id = s.company_id
  left join accounting_period_trial_balance_lines l
    on l.snapshot_id = s.id
  where p.status = 'CLOSED'
  group by s.id, s.company_id, s.accounting_period_id
)
select
  s.company_id,
  s.accounting_period_id,
  s.id as snapshot_id,
  s.trial_balance_total_debit,
  s.trial_balance_total_credit,
  st.line_debit,
  st.line_credit
from accounting_period_snapshots s
join snapshot_totals st
  on st.snapshot_id = s.id
where abs(coalesce(s.trial_balance_total_debit, 0) - coalesce(st.line_debit, 0)) > 0.01
   or abs(coalesce(s.trial_balance_total_credit, 0) - coalesce(st.line_credit, 0)) > 0.01
order by s.company_id, s.accounting_period_id, s.id;

-- 12) Flyway history mismatch vs repo expectations (NO-GO)
-- Update expected values on each release commit.
with expected as (
  select 135::int as expected_count, 135::int as expected_max_version
),
actual as (
  select
    count(*)::int as actual_count,
    coalesce(max(case when version ~ '^[0-9]+$' then version::int end), 0) as actual_max_version
  from flyway_schema_history
  where success = true
)
select
  expected.expected_count,
  expected.expected_max_version,
  actual.actual_count,
  actual.actual_max_version
from expected, actual
where actual.actual_count <> expected.expected_count
   or actual.actual_max_version <> expected.expected_max_version;

-- 13) Journal reference uniqueness drift (extra constraints/indexes) (NO-GO)
with canonical as (
  select c.conindid as index_oid
  from pg_constraint c
  join pg_class t on t.oid = c.conrelid
  join pg_namespace n on n.oid = t.relnamespace
  where n.nspname = 'public'
    and t.relname = 'journal_entries'
    and c.conname = 'uk_journal_company_reference'
),
extra_constraints as (
  select c.conname
  from pg_constraint c
  join pg_class t on t.oid = c.conrelid
  join pg_namespace n on n.oid = t.relnamespace
  join unnest(c.conkey) with ordinality as cols(attnum, ord) on true
  join pg_attribute a on a.attrelid = t.oid and a.attnum = cols.attnum
  where n.nspname = 'public'
    and t.relname = 'journal_entries'
    and c.contype = 'u'
  group by c.conname
  having array_agg(a.attname::text order by a.attname) = array['company_id', 'reference_number']
     and c.conname <> 'uk_journal_company_reference'
),
extra_indexes as (
  select idx.oid as index_oid, idx.relname
  from pg_index i
  join pg_class idx on idx.oid = i.indexrelid
  join pg_class t on t.oid = i.indrelid
  join pg_namespace n on n.oid = t.relnamespace
  join unnest(i.indkey) with ordinality as cols(attnum, ord) on true
  join pg_attribute a on a.attrelid = t.oid and a.attnum = cols.attnum
  where n.nspname = 'public'
    and t.relname = 'journal_entries'
    and i.indisunique
  group by idx.oid, idx.relname
  having array_agg(a.attname::text order by a.attname) = array['company_id', 'reference_number']
)
select 'journal_entries' as table_name, 'missing_constraint' as kind, 'uk_journal_company_reference' as name
where not exists (select 1 from canonical)
union all
select 'journal_entries', 'constraint', conname from extra_constraints
union all
select 'journal_entries', 'index', ei.relname
from extra_indexes ei
left join canonical c on c.index_oid = ei.index_oid
where c.index_oid is null;

-- 14) Accounting events uniqueness drift (extra constraints/indexes) (NO-GO)
with canonical as (
  select c.conindid as index_oid
  from pg_constraint c
  join pg_class t on t.oid = c.conrelid
  join pg_namespace n on n.oid = t.relnamespace
  where n.nspname = 'public'
    and t.relname = 'accounting_events'
    and c.conname = 'uk_aggregate_sequence'
),
extra_constraints as (
  select c.conname
  from pg_constraint c
  join pg_class t on t.oid = c.conrelid
  join pg_namespace n on n.oid = t.relnamespace
  join unnest(c.conkey) with ordinality as cols(attnum, ord) on true
  join pg_attribute a on a.attrelid = t.oid and a.attnum = cols.attnum
  where n.nspname = 'public'
    and t.relname = 'accounting_events'
    and c.contype = 'u'
  group by c.conname
  having array_agg(a.attname::text order by a.attname) = array['aggregate_id', 'sequence_number']
     and c.conname <> 'uk_aggregate_sequence'
),
extra_indexes as (
  select idx.oid as index_oid, idx.relname
  from pg_index i
  join pg_class idx on idx.oid = i.indexrelid
  join pg_class t on t.oid = i.indrelid
  join pg_namespace n on n.oid = t.relnamespace
  join unnest(i.indkey) with ordinality as cols(attnum, ord) on true
  join pg_attribute a on a.attrelid = t.oid and a.attnum = cols.attnum
  where n.nspname = 'public'
    and t.relname = 'accounting_events'
    and i.indisunique
  group by idx.oid, idx.relname
  having array_agg(a.attname::text order by a.attname) = array['aggregate_id', 'sequence_number']
)
select 'accounting_events' as table_name, 'missing_constraint' as kind, 'uk_aggregate_sequence' as name
where not exists (select 1 from canonical)
union all
select 'accounting_events', 'constraint', conname from extra_constraints
union all
select 'accounting_events', 'index', ei.relname
from extra_indexes ei
left join canonical c on c.index_oid = ei.index_oid
where c.index_oid is null
union all
select 'accounting_events', 'index', 'idx_acct_events_aggregate'
where to_regclass('idx_acct_events_aggregate') is not null;

-- 15) Index consolidation drift (NO-GO)
select 'index' as kind, 'idx_finished_goods_stock' as name
where to_regclass('idx_finished_goods_stock') is not null
union all
select 'index', 'idx_finished_good_batches_quantity'
where to_regclass('idx_finished_good_batches_quantity') is not null;

-- 16) AR subledger vs AR control-account mismatch (NO-GO)
with ar_accounts as (
  select distinct company_id, receivable_account_id as account_id
  from dealers
  where receivable_account_id is not null
),
ar_gl as (
  select
    aa.company_id,
    coalesce(sum(a.balance), 0) as gl_total
  from ar_accounts aa
  join accounts a
    on a.company_id = aa.company_id
   and a.id = aa.account_id
  group by aa.company_id
),
ar_subledger as (
  select
    i.company_id,
    coalesce(sum(i.outstanding_amount), 0) as subledger_total
  from invoices i
  where upper(coalesce(i.status, '')) not in ('VOID', 'DRAFT', 'REVERSED')
  group by i.company_id
)
select
  coalesce(g.company_id, s.company_id) as company_id,
  coalesce(g.gl_total, 0) as ar_gl_total,
  coalesce(s.subledger_total, 0) as ar_subledger_total,
  coalesce(g.gl_total, 0) - coalesce(s.subledger_total, 0) as variance
from ar_gl g
full join ar_subledger s
  on s.company_id = g.company_id
where abs(coalesce(g.gl_total, 0) - coalesce(s.subledger_total, 0)) > 0.01
order by company_id;

-- 17) AP subledger vs AP control-account mismatch (NO-GO)
with ap_accounts as (
  select distinct company_id, payable_account_id as account_id
  from suppliers
  where payable_account_id is not null
),
ap_gl as (
  select
    aa.company_id,
    coalesce(sum(a.balance), 0) as gl_total
  from ap_accounts aa
  join accounts a
    on a.company_id = aa.company_id
   and a.id = aa.account_id
  group by aa.company_id
),
ap_purchase_outstanding as (
  select
    p.company_id,
    coalesce(sum(p.outstanding_amount), 0) as purchase_outstanding_total
  from raw_material_purchases p
  where upper(coalesce(p.status, '')) not in ('VOID', 'DRAFT', 'REVERSED')
  group by p.company_id
),
ap_unapplied_supplier_credits as (
  select
    psa.company_id,
    coalesce(sum(psa.allocation_amount), 0) as unapplied_credit_total
  from partner_settlement_allocations psa
  join journal_entries je
    on je.company_id = psa.company_id
   and je.id = psa.journal_entry_id
  where upper(coalesce(psa.partner_type, '')) = 'SUPPLIER'
    and psa.purchase_id is null
    and psa.supplier_id is not null
    and upper(coalesce(je.status, '')) not in ('VOIDED', 'REVERSED', 'DRAFT')
  group by psa.company_id
),
ap_subledger as (
  select
    coalesce(po.company_id, uc.company_id) as company_id,
    coalesce(po.purchase_outstanding_total, 0) - coalesce(uc.unapplied_credit_total, 0) as subledger_total
  from ap_purchase_outstanding po
  full join ap_unapplied_supplier_credits uc
    on uc.company_id = po.company_id
)
select
  coalesce(g.company_id, s.company_id) as company_id,
  coalesce(g.gl_total, 0) as ap_gl_total,
  coalesce(s.subledger_total, 0) as ap_subledger_total,
  coalesce(g.gl_total, 0) - coalesce(s.subledger_total, 0) as variance
from ap_gl g
full join ap_subledger s
  on s.company_id = g.company_id
where abs(coalesce(g.gl_total, 0) - coalesce(s.subledger_total, 0)) > 0.01
order by company_id;

-- 18) Dispatched slips without dispatch inventory movements (NO-GO)
select
  p.company_id,
  p.id as packaging_slip_id,
  p.slip_number,
  p.status,
  count(m.id) as dispatch_movement_count
from packaging_slips p
left join inventory_movements m
  on m.packing_slip_id = p.id
 and upper(coalesce(m.movement_type, '')) = 'DISPATCH'
where upper(coalesce(p.status, '')) = 'DISPATCHED'
group by p.company_id, p.id, p.slip_number, p.status
having count(m.id) = 0
order by p.company_id, p.id;

-- 19) Dispatch inventory movements not linked to slip COGS journal (NO-GO)
select
  p.company_id,
  p.id as packaging_slip_id,
  p.slip_number,
  p.cogs_journal_entry_id,
  m.id as inventory_movement_id,
  m.journal_entry_id
from packaging_slips p
join inventory_movements m
  on m.packing_slip_id = p.id
 and upper(coalesce(m.movement_type, '')) = 'DISPATCH'
where upper(coalesce(p.status, '')) = 'DISPATCHED'
  and (
    p.cogs_journal_entry_id is null
    or m.journal_entry_id is null
    or m.journal_entry_id <> p.cogs_journal_entry_id
  )
order by p.company_id, p.id, m.id;
