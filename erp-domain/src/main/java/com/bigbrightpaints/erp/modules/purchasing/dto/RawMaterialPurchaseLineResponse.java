package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.math.BigDecimal;

public record RawMaterialPurchaseLineResponse(Long rawMaterialId,
                                              String rawMaterialName,
                                              Long rawMaterialBatchId,
                                              String batchCode,
                                              BigDecimal quantity,
                                              String unit,
                                              BigDecimal costPerUnit,
                                              BigDecimal lineTotal,
                                              String notes) {}
