ALTER TABLE reconciliation_discrepancies
    DROP CONSTRAINT IF EXISTS chk_recon_discrepancy_resolution;

ALTER TABLE reconciliation_discrepancies
    ADD CONSTRAINT chk_recon_discrepancy_resolution
        CHECK (
            resolution IS NULL
                OR resolution IN (
                    'ACKNOWLEDGED',
                    'ADJUSTMENT',
                    'ADJUSTMENT_JOURNAL',
                    'WRITE_OFF',
                    'CORRECTION'
                )
            );
