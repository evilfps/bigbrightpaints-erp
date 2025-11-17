DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'raw_material_movements'
    ) THEN
        ALTER TABLE raw_material_movements
            ADD COLUMN IF NOT EXISTS journal_entry_id BIGINT;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.conrelid = 'raw_material_movements'::regclass
              AND c.contype = 'f'
              AND a.attname = 'journal_entry_id'
        ) THEN
            ALTER TABLE raw_material_movements
                ADD CONSTRAINT fk_raw_material_movements_journal_entry
                FOREIGN KEY (journal_entry_id)
                REFERENCES journal_entries(id)
                ON DELETE SET NULL;
        END IF;

        CREATE INDEX IF NOT EXISTS idx_raw_material_movements_journal_entry
            ON raw_material_movements (journal_entry_id);
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'inventory_movements'
    ) THEN
        ALTER TABLE inventory_movements
            ADD COLUMN IF NOT EXISTS journal_entry_id BIGINT;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'fk_inventory_movements_journal_entry'
        ) THEN
            ALTER TABLE inventory_movements
                ADD CONSTRAINT fk_inventory_movements_journal_entry
                FOREIGN KEY (journal_entry_id)
                REFERENCES journal_entries(id)
                ON DELETE SET NULL;
        END IF;

        CREATE INDEX IF NOT EXISTS idx_inventory_movements_journal_entry
            ON inventory_movements (journal_entry_id);
    END IF;
END $$;
