package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PackagingSlipLineDto(Long id,
                                   UUID batchPublicId,
                                   String batchCode,
                                   BigDecimal quantity,
                                   BigDecimal unitCost) {}
