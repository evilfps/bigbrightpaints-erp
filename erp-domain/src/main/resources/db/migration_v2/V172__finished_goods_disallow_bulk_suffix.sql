-- ERP-23 hard cut: sellable finished-goods cannot use the internal semi-finished suffix.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM finished_goods
        WHERE RIGHT(UPPER(product_code), 5) = '-BULK'
    ) THEN
        RAISE EXCEPTION
            'ERP-23 migration blocked: found finished_goods.product_code values ending with -BULK. Move or rename those internal semi-finished rows before applying V172.';
    END IF;

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
