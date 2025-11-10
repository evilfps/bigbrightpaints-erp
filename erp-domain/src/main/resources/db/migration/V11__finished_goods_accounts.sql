ALTER TABLE finished_goods
    ADD COLUMN IF NOT EXISTS cogs_account_id BIGINT;
