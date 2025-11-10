package com.bigbrightpaints.erp.modules.factory.dto;

import java.time.Instant;
import java.util.UUID;

public record ProductionBatchDto(Long id,
                                 UUID publicId,
                                 String batchNumber,
                                 double quantityProduced,
                                 Instant producedAt,
                                 String loggedBy,
                                 String notes) {}
