package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CostBreakdownDto(
    Long productionLogId,
    String productionCode,
    String productName,
    String batchColour,
    BigDecimal mixedQuantity,
    BigDecimal materialCostTotal,
    BigDecimal laborCostTotal,
    BigDecimal overheadCostTotal,
    BigDecimal totalCost,
    BigDecimal unitCost,
    Instant producedAt,
    CostComponentTraceDto costComponents,
    List<PackedBatchTraceDto> packedBatches,
    List<RawMaterialTraceDto> rawMaterialTrace) {

  @JsonProperty("materialCost")
  public BigDecimal materialCost() {
    return materialCostTotal;
  }

  @JsonProperty("labourCost")
  public BigDecimal labourCost() {
    return laborCostTotal;
  }

  @JsonProperty("overheadCost")
  public BigDecimal overheadCost() {
    return overheadCostTotal;
  }
}
