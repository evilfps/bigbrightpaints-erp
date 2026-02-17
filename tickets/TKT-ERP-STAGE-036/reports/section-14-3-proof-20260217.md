# Section 14.3 Proof Pack (TKT-ERP-STAGE-036)

- release_head_sha: `3e8d9fe677da1b40ada34a8528c92e396f382015`
- release_anchor_sha: `07cc472ea5e087ada11caefa25ef68dab3b86005`

## Commands

1. `DIFF_BASE=07cc472ea5e087ada11caefa25ef68dab3b86005 GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
2. `bash scripts/gate_core.sh`
3. `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_reconciliation.sh`
4. `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_release.sh`

## Results

- All four gates passed on one head SHA.
- `gate_fast` changed-files coverage summary (non-vacuous):
  - files_considered: 2
  - line_ratio: 1.0
  - branch_ratio: 1.0
- `gate_release` traceability manifest reports release_sha matching `3e8d9fe677da1b40ada34a8528c92e396f382015`.

## Logs

- `/tmp/tkt036_gate_fast_anchor07cc.log`
- `/tmp/tkt036_gate_core.log`
- `/tmp/tkt036_gate_reconciliation.log`
- `/tmp/tkt036_gate_release.log`
