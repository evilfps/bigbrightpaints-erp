package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinishedGoodBatchDto(Long id,
                                   UUID publicId,
                                   String batchCode,
                                   BigDecimal quantityTotal,
                                   BigDecimal quantityAvailable,
                                   BigDecimal unitCost,
                                   Instant manufacturedAt,
                                   LocalDate expiryDate) {}
