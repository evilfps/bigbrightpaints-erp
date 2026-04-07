package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record RawMaterialStockEntryDto(
    Long materialId, String sku, String name, BigDecimal quantity) {}
