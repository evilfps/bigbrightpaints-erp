package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PackingLineRequest(
    @NotNull(message = "Sellable size target is required") Long childFinishedGoodId,
    Integer childBatchCount,
    @NotBlank(message = "Packaging size is required") String packagingSize,
    @Positive(message = "Quantity must be positive") BigDecimal quantityLiters,
    @Positive(message = "Pieces count must be positive") Integer piecesCount,
    @Positive(message = "Boxes count must be positive") Integer boxesCount,
    @Positive(message = "Pieces per box must be positive") Integer piecesPerBox) {
  public PackingLineRequest(
      String packagingSize,
      BigDecimal quantityLiters,
      Integer piecesCount,
      Integer boxesCount,
      Integer piecesPerBox) {
    this(null, null, packagingSize, quantityLiters, piecesCount, boxesCount, piecesPerBox);
  }
}
