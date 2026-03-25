package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProductionLogRequest(
    @NotNull(message = "Brand is required") Long brandId,
    @NotNull(message = "Product is required") Long productId,
    String batchColour,
    @NotNull(message = "Batch size is required") BigDecimal batchSize,
    String unitOfMeasure,
    @NotNull(message = "Mixed quantity is required") BigDecimal mixedQuantity,
    String producedAt,
    String notes,
    String createdBy,
    Long salesOrderId,
    BigDecimal laborCost,
    BigDecimal overheadCost,
    @Valid @NotEmpty(message = "Materials are required") List<MaterialUsageRequest> materials) {
  public record MaterialUsageRequest(
      @NotNull(message = "Raw material is required") Long rawMaterialId,
      @NotNull(message = "Quantity is required") @Positive(message = "Quantity must be positive")
          BigDecimal quantity,
      String unitOfMeasure) {}
}
