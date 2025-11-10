package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RawMaterialDto(Long id,
                             UUID publicId,
                             String name,
                             String sku,
                             String unitType,
                             BigDecimal reorderLevel,
                             BigDecimal currentStock,
                             BigDecimal minStock,
                             BigDecimal maxStock,
                             String stockStatus) {}
