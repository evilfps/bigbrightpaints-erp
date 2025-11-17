package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BankReconciliationRequest(
        @NotNull Long bankAccountId,
        @NotNull LocalDate statementDate,
        @NotNull BigDecimal statementEndingBalance,
        LocalDate startDate,
        LocalDate endDate,
        List<String> clearedReferences,
        Long accountingPeriodId,
        Boolean markAsComplete,
        String note) {}

