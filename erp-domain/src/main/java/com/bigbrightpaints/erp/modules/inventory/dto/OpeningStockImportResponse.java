package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;

public record OpeningStockImportResponse(
    String openingStockBatchKey,
    boolean preview,
    int rowsProcessed,
    int rawMaterialBatchesCreated,
    int finishedGoodBatchesCreated,
    List<ImportRowResult> results,
    List<ImportError> errors) {

  @JsonProperty("importedCount")
  public int importedCount() {
    return preview ? 0 : rowsProcessed;
  }

  public record ImportRowResult(
      long rowNumber,
      String sku,
      String stockType,
      String batchCode,
      BigDecimal quantity,
      BigDecimal unitCost,
      String entryMode,
      BigDecimal enteredQuantity,
      Integer piecesPerBox,
      SkuReadinessDto readiness) {

    public ImportRowResult withReadiness(SkuReadinessDto sanitizedReadiness) {
      return new ImportRowResult(
          rowNumber,
          sku,
          stockType,
          batchCode,
          quantity,
          unitCost,
          entryMode,
          enteredQuantity,
          piecesPerBox,
          sanitizedReadiness);
    }
  }

  public record ImportError(
      long rowNumber, String message, String sku, String stockType, SkuReadinessDto readiness) {}
}
