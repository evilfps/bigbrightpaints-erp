-- Backfill dealer/supplier ledger linkage to journal entries.
UPDATE dealer_ledger_entries dle
SET journal_entry_id = je.id
FROM journal_entries je
WHERE dle.journal_entry_id IS NULL
  AND dle.company_id = je.company_id
  AND dle.reference_number = je.reference_number;

UPDATE dealer_ledger_entries dle
SET journal_entry_id = je.id
FROM journal_reference_mappings jrm
JOIN journal_entries je
  ON je.company_id = jrm.company_id
 AND je.reference_number = jrm.canonical_reference
WHERE dle.journal_entry_id IS NULL
  AND jrm.company_id = dle.company_id
  AND jrm.legacy_reference = dle.reference_number;

UPDATE supplier_ledger_entries sle
SET journal_entry_id = je.id
FROM journal_entries je
WHERE sle.journal_entry_id IS NULL
  AND sle.company_id = je.company_id
  AND sle.reference_number = je.reference_number;

UPDATE supplier_ledger_entries sle
SET journal_entry_id = je.id
FROM journal_reference_mappings jrm
JOIN journal_entries je
  ON je.company_id = jrm.company_id
 AND je.reference_number = jrm.canonical_reference
WHERE sle.journal_entry_id IS NULL
  AND jrm.company_id = sle.company_id
  AND jrm.legacy_reference = sle.reference_number;

CREATE INDEX IF NOT EXISTS idx_dealer_ledger_journal_entry
    ON dealer_ledger_entries (journal_entry_id);

CREATE INDEX IF NOT EXISTS idx_supplier_ledger_journal_entry
    ON supplier_ledger_entries (journal_entry_id);
