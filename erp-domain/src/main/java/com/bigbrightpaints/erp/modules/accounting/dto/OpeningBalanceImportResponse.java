package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record OpeningBalanceImportResponse(
    int successCount, int failureCount, int accountsCreated, List<ImportError> errors) {

  public OpeningBalanceImportResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public OpeningBalanceImportResponse(
      int rowsProcessed, int accountsCreated, List<ImportError> errors) {
    this(rowsProcessed, errors == null ? 0 : errors.size(), accountsCreated, errors);
  }

  public int rowsProcessed() {
    return successCount;
  }

  public record ImportError(long rowNumber, String message) {}
}
