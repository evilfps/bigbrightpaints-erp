package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record OpeningBalanceImportResponse(
    int successCount, int failureCount, int accountsCreated, List<ImportError> errors) {

  public OpeningBalanceImportResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public static OpeningBalanceImportResponse fromSuccessfulRows(
      int successCount, int accountsCreated, List<ImportError> errors) {
    return new OpeningBalanceImportResponse(
        Math.max(successCount, 0), errors == null ? 0 : errors.size(), accountsCreated, errors);
  }

  public int rowsProcessed() {
    return successCount + failureCount;
  }

  public record ImportError(long rowNumber, String message) {}
}
