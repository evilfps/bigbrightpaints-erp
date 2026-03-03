package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record OpeningBalanceImportResponse(
        int rowsProcessed,
        int accountsCreated,
        List<ImportError> errors
) {
    public record ImportError(long rowNumber, String message) {}
}
