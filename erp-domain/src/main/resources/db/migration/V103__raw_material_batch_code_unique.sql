-- Deduplicate raw material batch codes per material before enforcing uniqueness.
WITH ranked AS (
    SELECT id,
           raw_material_id,
           batch_code,
           ROW_NUMBER() OVER (PARTITION BY raw_material_id, batch_code ORDER BY id) AS rn
    FROM raw_material_batches
),
updates AS (
    SELECT id,
           batch_code AS old_code,
           LEFT(
               batch_code,
               GREATEST(0, 128 - (5 + char_length(id::text)))
           ) || '-DUP-' || id AS new_code
    FROM ranked
    WHERE rn > 1
),
applied AS (
    UPDATE raw_material_batches b
    SET batch_code = u.new_code
    FROM updates u
    WHERE b.id = u.id
    RETURNING b.id, u.old_code, u.new_code
),
updated_items AS (
    UPDATE raw_material_purchase_items i
    SET batch_code = a.new_code
    FROM applied a
    WHERE i.raw_material_batch_id = a.id
    RETURNING i.id
)
UPDATE raw_material_movements m
SET reference_id = a.new_code
FROM applied a
WHERE m.raw_material_batch_id = a.id
  AND m.reference_type IN ('RAW_MATERIAL_PURCHASE', 'OPENING_STOCK')
  AND m.reference_id = a.old_code;

ALTER TABLE raw_material_batches
    ADD CONSTRAINT uq_raw_material_batches_code UNIQUE (raw_material_id, batch_code);
