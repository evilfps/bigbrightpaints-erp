package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FinishedGoodStockSummaryDto(
    Long finishedGoodId,
    String productCode,
    String name,
    BigDecimal totalStock,
    BigDecimal reservedStock,
    BigDecimal availableStock,
    BigDecimal weightedAverageCost) {

  /**
   * Legacy parity probes still read stock summary rows by `code` and `currentStock`. Keep those
   * aliases available while retaining the consolidated finished-good stock summary shape.
   */
  @JsonProperty("code")
  public String code() {
    return productCode;
  }

  @JsonProperty("currentStock")
  public BigDecimal currentStock() {
    return totalStock;
  }
}
