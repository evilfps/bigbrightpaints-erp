package com.bigbrightpaints.erp.modules.inventory.dto;

import java.time.LocalDate;

public record InventoryAdjustmentReversalRequest(LocalDate reversalDate,
                                                 String reason,
                                                 Boolean adminOverride,
                                                 String idempotencyKey) {
}
