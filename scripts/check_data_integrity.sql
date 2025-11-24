-- Quick integrity checks to run before applying new constraints/indexes

-- Accounts that would violate non-negative rule for assets/expenses/COGS
select company_id, code, type, balance
from accounts
where type in ('ASSET','EXPENSE','COGS') and balance < 0;

-- Finished goods or batches with negative stock/quantities
select id, product_code, current_stock, reserved_stock from finished_goods where current_stock < 0 or reserved_stock < 0;
select id, batch_code, quantity_total, quantity_available from finished_good_batches where quantity_total < 0 or quantity_available < 0;
select id, batch_code, quantity from raw_material_batches where quantity < 0;

-- Sales orders with duplicate idempotency keys (new unique index will fail)
select company_id, idempotency_key, count(*) as cnt
from sales_orders
where idempotency_key is not null
group by company_id, idempotency_key
having count(*) > 1;

-- Companies missing GST account configuration
select id, code, gst_input_tax_account_id, gst_output_tax_account_id
from companies
where gst_input_tax_account_id is null or gst_output_tax_account_id is null;
