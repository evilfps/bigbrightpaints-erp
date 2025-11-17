-- Placeholder migration to preserve version continuity between V24 and V26.
-- This ensures Flyway can run cleanly on fresh databases where numbering gaps would fail validation.
DO $$
BEGIN
    RAISE NOTICE 'V25 placeholder migration applied to align version ordering.';
END
$$;
