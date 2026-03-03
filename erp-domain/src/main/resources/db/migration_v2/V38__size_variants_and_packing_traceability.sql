CREATE TABLE IF NOT EXISTS size_variants (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES production_products(id) ON DELETE CASCADE,
    size_label VARCHAR(64) NOT NULL,
    carton_quantity INTEGER NOT NULL,
    liters_per_unit NUMERIC(19,4) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_size_variants_company_product_size UNIQUE (company_id, product_id, size_label),
    CONSTRAINT chk_size_variants_carton_quantity_positive CHECK (carton_quantity > 0),
    CONSTRAINT chk_size_variants_liters_per_unit_positive CHECK (liters_per_unit > 0)
);

CREATE INDEX IF NOT EXISTS idx_size_variants_company_product
    ON size_variants(company_id, product_id);

CREATE INDEX IF NOT EXISTS idx_size_variants_company_product_size
    ON size_variants(company_id, product_id, LOWER(size_label));

ALTER TABLE packing_records
    ADD COLUMN IF NOT EXISTS size_variant_id BIGINT REFERENCES size_variants(id) ON DELETE SET NULL;

ALTER TABLE packing_records
    ADD COLUMN IF NOT EXISTS child_batch_count INTEGER;

CREATE INDEX IF NOT EXISTS idx_packing_records_size_variant
    ON packing_records(size_variant_id);

ALTER TABLE raw_material_movements
    ADD COLUMN IF NOT EXISTS packing_record_id BIGINT REFERENCES packing_records(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_raw_material_movements_packing_record
    ON raw_material_movements(packing_record_id);

INSERT INTO size_variants (
    company_id,
    product_id,
    size_label,
    carton_quantity,
    liters_per_unit,
    active
)
SELECT
    p.company_id,
    pcs.product_id,
    pcs.size_label,
    pcs.pieces_per_carton,
    CASE
        WHEN regexp_replace(pcs.size_label, '[^0-9.]', '', 'g') ~ '^[0-9]+(\\.[0-9]+)?$'
            THEN CAST(regexp_replace(pcs.size_label, '[^0-9.]', '', 'g') AS NUMERIC(19,4))
        ELSE 1.0000
    END,
    TRUE
FROM production_product_carton_sizes pcs
JOIN production_products p ON p.id = pcs.product_id
ON CONFLICT (company_id, product_id, size_label) DO NOTHING;

UPDATE packing_records pr
SET size_variant_id = sv.id
FROM production_logs pl
JOIN size_variants sv
    ON sv.company_id = pl.company_id
   AND sv.product_id = pl.product_id
WHERE pr.production_log_id = pl.id
  AND LOWER(sv.size_label) = LOWER(pr.packaging_size)
  AND pr.size_variant_id IS NULL;

UPDATE raw_material_movements rmm
SET packing_record_id = pr.id
FROM packing_records pr
JOIN production_logs pl ON pl.id = pr.production_log_id
WHERE rmm.reference_type = 'PACKING_RECORD'
  AND rmm.reference_id = (pl.production_code || '-PACK-' || pr.id)
  AND rmm.packing_record_id IS NULL;
