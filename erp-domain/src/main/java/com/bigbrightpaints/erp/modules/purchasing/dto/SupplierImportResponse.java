package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.util.List;

public record SupplierImportResponse(int successCount, int failureCount, List<ImportError> errors) {

  public SupplierImportResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public int rowsProcessed() {
    return successCount + failureCount;
  }

  public record ImportError(long rowNumber, String message) {}
}
