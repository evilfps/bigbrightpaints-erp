# Run Backlog (Autonomous)

Last refreshed: 2026-02-06T12:27:00Z

## In Progress
- [ ] Create immutable candidate commit containing non-vacuous gate-fast enforcement + runtime truthsuite coverage.

## Next
- [ ] Stage only intended files (no `artifacts/`) and commit candidate SHA.
- [ ] Re-run all five gates once on the committed SHA for final certification output.
- [ ] Publish promotion recommendation for branch-as-trunk flow.

## Done
- [x] Diagnosed non-vacuous gate-fast failure against baseline `2df86f7...`.
- [x] Added vacuous coverage detection to `scripts/changed_files_coverage.py`.
- [x] Enforced fail-on-vacuous in `scripts/gate_fast.sh` release validation mode.
- [x] Added executable critical truthsuite runtime tests covering:
  - `AccountingFacade.reverseClosingEntryForPeriodReopen`
  - `AccountingPeriodService` close/reopen facade boundary paths
  - `InventoryValuationService` FIFO quantity-available valuation
- [x] Updated `TEST_CATALOG.json` and gate contracts.
- [x] Passed all five gates in working tree with non-vacuous `gate-fast`.
