# Factory Playwright Journeys

## 1. Production Log Creation

1. Log in as a factory user.
2. Open `/factory/production` and select a ready SKU or work order.
3. Assert stock, packaging, and readiness data are available.
4. Create a production log with quantity and batch context.
5. Assert the production log appears in the list.

## 2. Packing Record Creation

1. Open the production output that is ready for packing.
2. Select the correct packaging mapping.
3. Create a packing record with quantity and batch references.
4. Assert the packing record appears in the list.
5. Assert packaging units and batch lineage are visible.

## 3. Dispatch Confirmation

1. Open `/factory/dispatch/pending` and select a dispatch slip.
2. Open dispatch preview and slip detail.
3. Assert quantities, order references, transporter metadata, and shipment
   readiness are visible.
4. Click dispatch confirm.
5. Assert success state after backend confirm succeeds.
6. Refresh slip detail and assert challan number, PDF link, journal references,
   and final dispatch status are visible.
7. Assert invoice browser controls are not visible.

## 4. Partial Dispatch Handling

1. Open a dispatch slip with partial quantity.
2. Confirm partial dispatch.
3. Assert the slip remains in the queue with remaining quantity context.
4. Assert the dispatched portion is tracked correctly.

## 5. Failed Dispatch Handling

1. Open a dispatch slip that will fail confirmation.
2. Attempt dispatch confirm.
3. Assert the exact mismatch, batch, or idempotency error is visible.
4. Assert the operator stays on the slip detail screen.
5. Assert accounting or sales recovery shortcuts are not visible.

## 6. Production Correction

1. Open an existing production log that needs correction.
2. Edit the production log quantity.
3. Assert packing and dispatch-read state are invalidated.
4. Assert re-entering packing or dispatch is required from the corrected state.
5. If dispatch already posted, assert accounting correction is not offered.
