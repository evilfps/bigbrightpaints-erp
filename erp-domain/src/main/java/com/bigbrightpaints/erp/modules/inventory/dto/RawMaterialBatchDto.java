package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RawMaterialBatchDto(Long id,
                                  UUID publicId,
                                  String batchCode,
                                  BigDecimal quantity,
                                  String unit,
                                  BigDecimal costPerUnit,
                                  String supplier,
                                  Instant receivedAt,
                                  String notes) {}
