package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record InventoryAdjustmentLineDto(Long finishedGoodId,
                                         String finishedGoodName,
                                         BigDecimal quantity,
                                         BigDecimal unitCost,
                                         BigDecimal amount,
                                         String note) {}
