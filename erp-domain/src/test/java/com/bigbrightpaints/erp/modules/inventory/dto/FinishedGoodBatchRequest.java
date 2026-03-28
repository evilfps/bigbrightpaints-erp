package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record FinishedGoodBatchRequest(
    Long finishedGoodId,
    String batchCode,
    BigDecimal quantity,
    BigDecimal unitCost,
    Instant manufacturedAt,
    LocalDate expiryDate) {}
