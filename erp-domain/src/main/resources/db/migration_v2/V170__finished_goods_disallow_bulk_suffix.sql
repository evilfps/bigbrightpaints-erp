-- ERP-23 hard cut: sellable finished-goods cannot use the internal semi-finished suffix.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_finished_goods_no_bulk_suffix'
    ) THEN
        ALTER TABLE finished_goods
            ADD CONSTRAINT chk_finished_goods_no_bulk_suffix
            CHECK (RIGHT(UPPER(product_code), 5) <> '-BULK');
    END IF;
END $$;
