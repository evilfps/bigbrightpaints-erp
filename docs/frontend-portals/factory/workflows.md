# Factory Workflows

## 1. Ready SKU To Production Log

1. Open a SKU or work order that is ready for factory execution.
2. Confirm required stock, packaging, and readiness data are available.
3. Create the production log.
4. Record the produced quantity and batch context.

## 2. Production To Packing

1. Move from posted production output into packing.
2. Select the correct produced stock and packaging mapping.
3. Create the packing record.
4. Verify packed quantity, packaging units, and batch references are valid.

## 3. Packing To Dispatch Confirm

1. Open the pending dispatch queue.
2. Select the dispatch slip tied to the packed stock.
3. Load dispatch preview and slip detail so the operator can review quantities,
   order references, transporter metadata, and shipment readiness.
4. Submit `POST /api/v1/dispatch/confirm`.
5. Show success state only after the backend confirm succeeds.
6. Refresh slip detail and render challan number, challan PDF link, journal
   references, and the final dispatch status.
7. Do not replace this with an invoice browser. If the operator needs invoice
   follow-up, route them to sales or dealer-client read surfaces.

## 4. Partial And Failed Dispatch Handling

1. If dispatch is partial, keep the slip visible in the queue with remaining
   quantity context.
2. If dispatch fails, keep the operator on the slip detail screen.
3. Show the exact mismatch, batch, or idempotency error returned by backend.
4. Do not route the operator into accounting or sales as a recovery shortcut.

## 5. Production Correction And Batch-Lineage Recovery

1. If a production log is corrected, reopen the production detail and batch
   lineage views.
2. Invalidate any stale packing or dispatch-read state that was built from the
   corrected production output.
3. Require the operator to re-enter packing or dispatch only from the corrected
   state returned by backend.
4. If dispatch already posted, do not offer factory-side accounting correction.
   Route the user to the linked operational read context only.

## Failure Handling

- SKU readiness blocked: show why execution cannot start.
- Packaging mapping missing: block packing until mapping is resolved.
- Quantity mismatch: block dispatch confirm and preserve unsent quantity
  details.
- Canonical retry conflict: show the dispatch reference and tell the user the
  action already posted or is still processing.
