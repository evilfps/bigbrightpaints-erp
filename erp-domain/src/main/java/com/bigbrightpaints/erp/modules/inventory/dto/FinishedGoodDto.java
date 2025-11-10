package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record FinishedGoodDto(Long id,
                              UUID publicId,
                              String productCode,
                              String name,
                              String unit,
                              BigDecimal currentStock,
                              BigDecimal reservedStock,
                              String costingMethod,
                              Long valuationAccountId,
                              Long cogsAccountId) {}
