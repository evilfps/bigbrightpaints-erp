package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;

public record CatalogItemStockDto(
        BigDecimal onHandQuantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        String unitOfMeasure
) {
}
