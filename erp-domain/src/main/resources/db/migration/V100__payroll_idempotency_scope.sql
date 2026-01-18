-- Ensure payroll run idempotency is scoped per company (no global unique constraint)
DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN unnest(c.conkey) WITH ORDINALITY AS cols(attnum, ord) ON TRUE
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = cols.attnum
        WHERE t.relname = 'payroll_runs'
          AND c.contype = 'u'
        GROUP BY c.conname
        HAVING array_agg(a.attname::text ORDER BY a.attname) = ARRAY['idempotency_key']
    LOOP
        EXECUTE format('ALTER TABLE payroll_runs DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END$$;

DO $$
DECLARE
    idx_name text;
BEGIN
    FOR idx_name IN
        SELECT idx.relname
        FROM pg_index i
        JOIN pg_class idx ON idx.oid = i.indexrelid
        JOIN pg_class tbl ON tbl.oid = i.indrelid
        JOIN pg_attribute a ON a.attrelid = tbl.oid AND a.attnum = ANY(i.indkey)
        WHERE tbl.relname = 'payroll_runs'
          AND i.indisunique
        GROUP BY idx.relname
        HAVING array_agg(a.attname::text ORDER BY a.attname) = ARRAY['idempotency_key']
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', idx_name);
    END LOOP;
END$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_payroll_runs_company_idempotency
    ON payroll_runs(company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
