# Factory Playwright Journeys

## 1. Production To Packing To Dispatch

1. Log in as a factory user.
2. Open `/factory/production/logs/new` and create a production log for a ready
   SKU.
3. Open `/factory/packing-records/new` and create a packing record from that
   produced stock.
4. Open `/factory/dispatch/pending`.
5. Select the slip and confirm dispatch.
6. Assert success state appears only after backend confirm returns success.
7. Assert the refreshed slip detail shows challan metadata and journal
   references.
8. Assert factory does not open or own a standalone invoice browser after the
   confirm action.

## 2. Packing Blocked By Missing Mapping

1. Log in as factory.
2. Open a production output that lacks a packaging mapping.
3. Attempt to create a packing record.
4. Assert the UI blocks save and shows the missing mapping dependency.

## 3. Dispatch Quantity Mismatch

1. Open a pending dispatch slip with mismatched quantity data.
2. Attempt dispatch confirm.
3. Assert the user remains on slip detail.
4. Assert the exact quantity mismatch error is shown and no success state is
   rendered.

## 4. Retry Conflict

1. Confirm a dispatch successfully.
2. Retry the same action from a stale page.
3. Assert the UI shows duplicate or already-processed state rather than posting
   a second shipment.

## 5. Production Correction Reopens Lineage

1. Open a production log that is later corrected by backend.
2. Refresh the log detail page.
3. Assert the UI marks the log as `CORRECTED`.
4. Assert stale packing or dispatch actions are cleared until the operator
   reopens the corrected batch lineage state.
