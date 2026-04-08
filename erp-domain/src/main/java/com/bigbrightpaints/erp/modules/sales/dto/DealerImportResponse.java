package com.bigbrightpaints.erp.modules.sales.dto;

import java.util.List;

public record DealerImportResponse(int successCount, int failureCount, List<ImportError> errors) {

  public DealerImportResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public int rowsProcessed() {
    return successCount + failureCount;
  }

  public record ImportError(long rowNumber, String message) {}
}
