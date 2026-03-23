ALTER TABLE opening_stock_imports
    ADD COLUMN IF NOT EXISTS opening_stock_batch_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS results_json TEXT;

UPDATE opening_stock_imports
SET opening_stock_batch_key = COALESCE(NULLIF(opening_stock_batch_key, ''), idempotency_key)
WHERE opening_stock_batch_key IS NULL
   OR opening_stock_batch_key = '';

WITH ranked_batch_keys AS (
    SELECT id,
           opening_stock_batch_key,
           ROW_NUMBER() OVER (
               PARTITION BY company_id, opening_stock_batch_key
               ORDER BY created_at ASC NULLS LAST, id ASC
           ) AS batch_rank
    FROM opening_stock_imports
    WHERE opening_stock_batch_key IS NOT NULL
      AND opening_stock_batch_key <> ''
), deduplicated_batch_keys AS (
    SELECT id,
           LEFT(
               opening_stock_batch_key,
               GREATEST(1, 128 - LENGTH('-LEGACY-') - LENGTH(id::text))
           ) || '-LEGACY-' || id::text AS deduplicated_batch_key
    FROM ranked_batch_keys
    WHERE batch_rank > 1
)
UPDATE opening_stock_imports imports
SET opening_stock_batch_key = deduplicated_batch_keys.deduplicated_batch_key
FROM deduplicated_batch_keys
WHERE imports.id = deduplicated_batch_keys.id;

ALTER TABLE opening_stock_imports
    ALTER COLUMN opening_stock_batch_key SET NOT NULL;

DROP INDEX IF EXISTS idx_opening_stock_import_company_batch_key;
DROP INDEX IF EXISTS idx_opening_stock_imports_replay_protection;
ALTER TABLE opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_opening_stock_import_company_batch_key
    ON opening_stock_imports(company_id, opening_stock_batch_key);
