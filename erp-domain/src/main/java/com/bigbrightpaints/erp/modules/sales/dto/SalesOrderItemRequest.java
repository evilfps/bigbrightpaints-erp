package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SalesOrderItemRequest(
    Long finishedGoodId,
    String productCode,
    String description,
    @NotNull @Positive BigDecimal quantity,
    @NotNull @Positive BigDecimal unitPrice,
    BigDecimal gstRate) {

  public SalesOrderItemRequest(
      String productCode,
      String description,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal gstRate) {
    this(null, productCode, description, quantity, unitPrice, gstRate);
  }

  public String normalizedProductCode() {
    if (productCode == null) {
      return "";
    }
    return productCode.trim();
  }

  public boolean hasFinishedGoodId() {
    return finishedGoodId != null;
  }

  public boolean hasProductCode() {
    return !normalizedProductCode().isBlank();
  }
}
