package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;

public record InventoryCountResponse(
        Long itemId,
        String itemName,
        InventoryCountTarget target,
        BigDecimal systemQuantity,
        BigDecimal physicalQuantity,
        BigDecimal varianceQuantity,
        BigDecimal varianceValue,
        boolean adjustmentPosted,
        Long journalEntryId,
        boolean alertRaised,
        String alertReason) {}

