alter table production_products
    add column if not exists variant_group_id uuid,
    add column if not exists product_family_name varchar(255);

create index if not exists idx_production_products_company_variant_group
    on production_products (company_id, variant_group_id);
