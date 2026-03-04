package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BankReconciliationSessionCreateRequest(
        @NotNull Long bankAccountId,
        @NotNull LocalDate statementDate,
        @NotNull BigDecimal statementEndingBalance,
        LocalDate startDate,
        LocalDate endDate,
        Long accountingPeriodId,
        String note) {
}
