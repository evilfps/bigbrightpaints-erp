package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UnpackedBatchDto(
    Long id,
    String productionCode,
    String productName,
    String batchColour,
    BigDecimal mixedQuantity,
    BigDecimal packedQuantity,
    BigDecimal remainingQuantity,
    String status,
    Instant producedAt,
    String productFamilyName,
    java.util.List<AllowedSellableSizeDto> allowedSellableSizes) {
  public UnpackedBatchDto(
      Long id,
      String productionCode,
      String productName,
      String batchColour,
      BigDecimal mixedQuantity,
      BigDecimal packedQuantity,
      BigDecimal remainingQuantity,
      String status,
      Instant producedAt) {
    this(
        id,
        productionCode,
        productName,
        batchColour,
        mixedQuantity,
        packedQuantity,
        remainingQuantity,
        status,
        producedAt,
        null,
        java.util.List.of());
  }
}
