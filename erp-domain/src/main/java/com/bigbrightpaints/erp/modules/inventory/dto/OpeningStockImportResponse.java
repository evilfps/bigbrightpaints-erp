package com.bigbrightpaints.erp.modules.inventory.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;

public record OpeningStockImportResponse(
    String openingStockBatchKey,
    int rowsProcessed,
    int rawMaterialBatchesCreated,
    int finishedGoodBatchesCreated,
    List<ImportRowResult> results,
    List<ImportError> errors) {

  @JsonProperty("importedCount")
  public int importedCount() {
    return rowsProcessed;
  }

  public record ImportRowResult(
      long rowNumber, String sku, String stockType, SkuReadinessDto readiness) {}

  public record ImportError(
      long rowNumber, String message, String sku, String stockType, SkuReadinessDto readiness) {}
}
