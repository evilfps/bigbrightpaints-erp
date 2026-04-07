package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FinishedGoodBatchInventoryDto(
    Long batchId, String batchCode, BigDecimal quantity, LocalDate expiryDate) {}
