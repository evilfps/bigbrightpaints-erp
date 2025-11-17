package com.bigbrightpaints.erp.modules.reports.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;

import java.math.BigDecimal;
import java.util.List;

public record TrialBalanceDto(
        List<Row> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced
) {
    public record Row(
            Long accountId,
            String code,
            String name,
            AccountType type,
            BigDecimal debit,
            BigDecimal credit
    ) {}
}
