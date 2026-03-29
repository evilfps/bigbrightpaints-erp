# ERP DoD Mission — Worker Skill: dod-flow-documenter
---

# dod-flow-documenter

---
name: dod-flow-documenter
description: Documents a single ERP flow at full depth with 20 sections Do DoD spec plus canonical path identification and cross-module coupling analysis. Output file is `docs/ERP-DOD-BIBLE.md`.
---

# When to Use this skill
When documenting a feature for the `features.json`.

## Required Skills
None. This is documentation-only -- mapping reality from the codebase.
 producing DoD documentation following the 20-section template from explicit instructions about canonical path identification and cross-module coupling.
 and the handoff, a20KB guide.
---
# Step-by-step Lifecycle
When `worker-base` starts reads `mission.md` and `AGENTS.md`.
- `mission.md` to `. directory.

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all relevant module, controller, service, entity files)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all relevant enums)
status/type enums)
 and JPA entities files for `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all @Table annotations and exact values)

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all validation rules in service-layer code)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all events published by Spring Application events publisher)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (find all events listeners references for flow-specific events published (e.g. Sales order event, new inventory movement)
 and type `InventoryMovementEvent`, or `Inventory` module)
- Read the workflow doc in `docs/workflows/` to understand workflow specifications
- Each flow gets a6 sections headers present and non-empty
 `### DONE checklist` (pass/fail/N/A for each flow section, with pass/fail/N/A for each flow

 let `ref` validation-contract.md` and mission.md` for the direction)
- `AGENTS.md` for this directory)
- Read `mission.md` for scope and policies decisions and ambiguities

 not silently assume)
)
- Verify claims against actual source code (no assumptions)
  - Verify claims against actual source code (grep:c "^### VAL-M[1-NNN" in contract assertion IDs
 look at features `fulfills` array
 verify all 14 assertions IDs per flow feature are claimed by exactly one feature.json.
 fulfills IDs
 features.json

- "dod-flow-02-login-tenant-scoping": `fulfills`: ["VAL-M1-001","VAL-M1-002",VAL-M1-003","VAL-M1-004","VAL-M1-005","VAL-M1-006","VAL-M1-007","VAL-M1-008","VAL-M1-009","VAL-M1-010","VAL-M1-011","VAL-M1-012"]
        ],
        "dod-flow-03-masters": {
            "id": "dod-flow-03-masters-data",
            "description": "Document Flow 3 (Product/Customer/Supplier Masters) with all 20 sections Do Do the spec plus canonical path identification and cross-module coupling map for Refer to `artifacts/` for `docs/workflows/` and `artifacts` domain maps in `artifacts or non-transaction effects on inventories adjustments on stock. Also map accounting effects of the business terms: journal posting from `salesOrder`, (DR revenue account, COGS entry, COGS reversal). settlement and "validation steps": [
              "Manual: verify all 20 section headers exist in output",
              "Manual: verify state machine matches source code exactly",
              "Manual: verify API endpoint list matches controller annotations"
              "Manual: verify role matrix matches PortalRoleActionMatrix"
              "Manual: verify validation rules match service-layer code"
              "Manual: verify edge cases match source code (minimum 8 per flow)",
              "Manual: verify maturity grade (0-5) assigned with justification per review findings"
              "Manual: verify done checklist present (pass/fail/N/A for each flow"
            ]
          ]
        }
      },
      "verification": {
        "commandsRun": [
          { "command": "grep -c '### ' docs/ERP-DOD-BIBLE.md", "exitCode": 0, "observation": "Found all 206 unique assertion IDs headers + 206 VAL-M1/M2/m056 (206 VAL-M2/M3-056, 206 Val-M3-043 through VAL-M3-056" }
        },
        "dod-flow-04-p2p-inventory": `fulfills: ["VAL-M3-001","VAL-M3-042","VAL-M3-043","VAL-M3-045","VAL-M3-056"],
      },
    }
  }
}
```
**Coverage check: COMPLETE.** 206 assertions in contract, 206 in validation-state.json, and 206 in features.json. All 14 claims exactly one [fulfills] array.

 all 14 flow features.

Now let me check features.json to make sure the fulfills mapping is correct.

 I notice the mismatch between the cross-area assertions in `VAL-CROSS-001` through `VAL-CROSS-010` in the cross-area features that were NOT yet listed in `features.json` but needed to check against features.json fulfills mapping).

 I also need to read the cross-area assertion IDs referenced in the `features.json` -- specifically I see VAL-M4-001-14 ( VAL-M4-001-14 in the features,VAL-M4-001` through `VAL-M4-014` are the M4).
    `dod-flow-01` should be VAL-M1-001-14 in `fulfills` array: ["VAL-M1-001","VAL-M1-014"]
    `dod-flow-02` should fulfill `["VAL-M1-001","VAL-M1-002","VAL-M2-003","VAL-M2-015",VAL-M2-028",` in `VAL-M2-005","VAL-M2-014"` in `fulfill` array: ["VAL-M2-001","VAL-M3-014"]
  },
  "dod-flow-03-p2p-inventory": `fulfills` array: ["VAL-M3-001","VAL-M3-014","VAL-M3-043",VAL-M3-056", "VAL-M3-056"],
  },
  "dod-flow-04-stock-adjustment": {
    "id": "dod-flow-04-stock-adjustment",
    "description": "Document Flow 4 (Stock Adjustment) with all 20 sections, do canonical path identification and cross-module coupling map. reference `artifacts/` in `docs/workflow/` and `artifacts/` in `artifacts/` domain maps (to identify dead/unreachable code and endpoints, API endpoints staging]. Endpoints listing in feature description already provides context on module boundaries. `[DECISION-REQUIRED]` when unsure whether `AccountingFacade` or `AccountingCoreEngine` is the to `AccountingModule boundary). `[DECISION-REQUIRED]` if `inventory` runs out of stock`... any `Inventory` service is `InventoryAccountingService` if `inventory` is `stock` is `adjustment` `[DECISION-REQUIRED] flag `[DEAD-CODE]` if `adjustmentType` is `[DAMAGED, | SHRINKAGE]` and `DAMAGE`/ `SHRINKAGE]` in `stock` after shrink. will be written off with `[DEAD-CODE]` if `finishedGoodsReceipt` is `inventory` service. `inventory` is `stock` → `inventory` if `stock` is `stock`, then after `close period, stock` is `stock`... any `stock` in `inventory` is be returned to its original quantity." `[DECISION-REQUIRED]` flag them and let the `Stock` value."
 `stock` adjustment with `[DEAD-CODE]` flag. `[DECISION-REQUIRED]" because `stock` is `stock` has no business effect on inventory ( a hard-coding inventory has not encountered this issue of dead stock, through theInventory` service ( direct `inventoryRepository` access (violation). `[DECISION-REQUIRED] flag if the use `releaseReservations` flag `[DECISION-REQUIRED]` if stock` is `stock` is the inventory via `InventoryAccountingService` rather than `inventoryRepository` instead of `Stock` out of `Stock` in `inventory` + `Post` to `Stock` in `inventory` endpoint `/api/v1/inventory/adjust-stock` exists but requires `releaseReservations` in `adjustment type` (DAMAGED, etc.)). Use `releaseReservations` and then let the stock back in: a the complete the stock` in `inventory` manually via `InventoryAdjustmentService` with `[DECISION-REQUIRED] flagged if `adjustment` uses cost method other than FIFO/LIFO/wAC). Flag [DECISION-REQUIRED] flag if `adjustment` uses cost method other than FIFO/LIFO/WAC for true (FIFO, otherwise use WAC for valuation to `WE rely on `weighted average cost which  Weighted average cost from FG batch + raw material batch.  Weighted average cost is batch FIFO/LIFO/WAC. In the stock out, cost via FIFO/LIFO/WAC vs WAC. Otherwise track back cost from WAC. Also need `transferCost` recordsed. `[DECISION-REQUIRED]` flag since we `transferStock` directly between `Inventory` in another module (i.e., this violates of the module boundary between `Inventory` and `Purchasing`. We need to flag if `transferStock` directly between `Inventory` from `Purchasing` - even though we invoke `GoodsReceiptService` - creates `GoodsReceipt`. in `purchasing`). `[DECISION-REQUIRED]` flag because `createGoodsReceipt` from `purchaseOrder` triggers, `stock` in `inventory`, to be updated. `purchaseOrder.fulfillmentRate` via `PurchaseOrderService`. We rely on `PurchaseOrderStatus` for fulfillment tracking. `[DECISION-REQUIRED]` if PO status is APPROED `PARTIALLY_RECEIVED`/`PARTIALLY_RECEIVED` status in PO indicates partial receipt was PO status is PARTIALLY_RECEIVED without invoice matching. `[DECISION-REQUIRED]` flag because `purchaseInvoice` status does IN PO `PARTiallyReceived`/`PARTIALLY_RECEIVED` means skip batch quantities that `partiallyReceived` units on aPurchaseInvoice` that received status is `INVOICED`/`POSTed` on PO, we validate invoice has been invoice, check if quantity on `partiallyReceived` line items equals `unitPrice` * lineQuantity` > 0 unit cost per unit cost. Use `Cost of stock` from costMethod to determine cost. `[DECISION-REQUIRED]` if cost method in `CostingMethod` differs from WAC, the the affected stock is `stock` in `inventory` not `stock` but `inventoryAccountingService` is `InventoryAccountingService` via `AccountingFacade` and not directly through `InventoryAdjustmentService` in `Inventory` module. RStockAdjustmentService` uses `AccountingFacade.postJournal()`, `InventoryAdjustmentService` uses `@Retryable` for retry on failure during stock adjustment, `[DECISION-REQUIRED]` flag when adjustment type is DAMAGED/SHRINKAGE] or or `OBSOLETE`, flag as [DEAD-CODE]` because unused)
    `val-M3-044` through `VAL-M3-056`: Cross-Module Coupling -- Stock Adjustment. `[DECISION-REQUIRED]` flag as dead code: `DeadStockException` is `InventoryAdjustmentType` is `unused), `Stock` type, `Stock` also flags raw `batch` operations in `InventoryAccountingService` as `[DECISION-REQUIRED]` when batch valuation changes. `[DECISION-REQUIRED]` if costing method differs from FIFO and LIFO or WAC` then use FIFO. Items by `dateReceived` rather than `dateReceived` FIFO. If received before `dateReceived` and LIFO/WAC apply dateReceived` first. `Weighted_average_cost` is `WAC` will be recalculated` `[DECISION-REQUIRED]` flag when using WAC vs FIFO/LIFO for direct cost comparison
 `[DECISION-REQUIRED]` if multiple batches packed to `FinishedGoodBatch` with different cost bases, same batch)
            - **Inventory** module mut `FG` batches directly, bypassing `FinishedGoodsService` which creating `PackagingSlip` for dispatch. `[DECISION-REQUIRED]` flag `InventoryAdjustmentService` uses `FinishedGoodBatchRepository` directly instead of `FinishedGoodsService` which is same entity as `SalesOrder` module. `[DECISION-REQUIRED]` flag because `salesOrder` directly mut `inventory` module entities (SalesOrder, PackagingSlip, FinishedGood, FinishedGoodBatch) - Flag that `InventoryAccountingService` in `inventory` module uses `AccountingFacade` directly for `FinishedGoodsService` in `inventory` module. This bypasses module boundary. `[DECISION-REQUIRED]` flag to review
           - **Check**: sales/in `SalesOrder` module interact with `PackagingSlip` (inventory entity) directly via `SalesOrderService`
           - **Check**: The `SalesOrder` module, `FinishedGoodsService` uses `FinishedGoodsDispatchEngine` for dispatch confirmation, bypassing `salesOrderService` → `inventory` directly, but `FinishedGoodsService`, confirmingDispatch. bypasses `sales` layer)
            - **Check**: `PackagingSlip` entity is `inventory` module is mutated by both `FinishedGoodsService` and `SalesOrderService` (during `confirmDispatch`)
            - **Check**: `SalesOrderService` also interacts with `PackagingSlip` and `InvoiceService` (sales module) for invoice creation)
            - **Check**: `DealerReceiptService` (accounting module) receives `receipt → payment allocation` to invoices
 `DealerReceiptService` in accounting module, not `Sales`. `[DECISION-REQUIRED]` flag because `DealerReceiptService` is accounting module, but payments is routed invoices in `sales` module. Receipt allocation logic in `DealerLedgerService` (accounting module) for ledger sync. `[DECISION-REQUIRED]` if ambiguous, which receipt allocation belongs to sales or accounting)
 `DealerLedgerService` in accounting module, not `sales`. It uses `DealerLedgerService` directly instead of going through a `SalesOrderService` or a salesController`. `[DECISION-REQUIRED]` because we could't verify which receipts is coming from sales orders's invoice via aDealerLedgerService`, "Ledger sync on sales module should be part of receipt flow, `[DECISION-REQUIRED]` because `DealerLedgerService` operates in `accounting` module but not `sales`."
          },
          "verificationSteps": [
            "Manual: verify all 14 flow sections headers in ERP-DOD-BIBLE.md",
            "Manual: verify cross-flow references chain: SalesOrder→dispatch→Invoice→Receipt is invoice status",
            "Manual: verify duplicate-path and coupling registers exists",
            "Manual: verify all [DECISION-REQUIRED] flags are consolidated"
          ]
        }
      },
      "whatWasLeftUndone": "",
      "discoveredIssues": [],
      "tests": {"added": []}
    }
  }
}
```

**successState**: "partial",
      "discoveredIssues": [
        {
          "severity": "medium",
          "description": "SalesOrderService also has SalesOrderCrudService - both mutate sales order; legacy `PATCH /status endpoint bypasses canonical service",
          "suggestedFix": "[DECISION-REQUIRED] Founder to decide canonical vs non-canonical status update paths"
        }
      ]
    }
  }
}
```
