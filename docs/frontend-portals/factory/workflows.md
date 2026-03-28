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
3. Review quantities, order references, and shipment readiness.
4. Submit `POST /api/v1/dispatch/confirm`.
5. Show success state only after the backend confirm succeeds.

## 4. Partial And Failed Dispatch Handling

1. If dispatch is partial, keep the slip visible in the queue with remaining
   quantity context.
2. If dispatch fails, keep the operator on the slip detail screen.
3. Show the exact mismatch, batch, or idempotency error returned by backend.
4. Do not route the operator into accounting or sales as a recovery shortcut.

## Failure Handling

- SKU readiness blocked: show why execution cannot start.
- Packaging mapping missing: block packing until mapping is resolved.
- Quantity mismatch: block dispatch confirm and preserve unsent quantity
  details.
- Canonical retry conflict: show the dispatch reference and tell the user the
  action already posted or is still processing.
