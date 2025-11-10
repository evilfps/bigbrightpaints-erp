package com.bigbrightpaints.erp.modules.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PackagingSlipDto(Long id,
                               UUID publicId,
                               Long salesOrderId,
                               String slipNumber,
                               String status,
                               Instant createdAt,
                               Instant dispatchedAt,
                               List<PackagingSlipLineDto> lines) {}
