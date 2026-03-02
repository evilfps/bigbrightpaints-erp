package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProfitLossDto(BigDecimal revenue,
                             BigDecimal costOfGoodsSold,
                             BigDecimal grossProfit,
                             BigDecimal operatingExpenses,
                             List<ExpenseCategory> operatingExpenseCategories,
                             BigDecimal netIncome,
                             ReportMetadata metadata,
                             Comparative comparative) {

    public ProfitLossDto(BigDecimal revenue,
                         BigDecimal costOfGoodsSold,
                         BigDecimal grossProfit,
                         BigDecimal operatingExpenses,
                         BigDecimal netIncome,
                         ReportMetadata metadata) {
        this(revenue,
                costOfGoodsSold,
                grossProfit,
                operatingExpenses,
                List.of(),
                netIncome,
                metadata,
                null);
    }

    public record ExpenseCategory(
            String category,
            BigDecimal amount
    ) {
    }

    public record Comparative(
            BigDecimal revenue,
            BigDecimal costOfGoodsSold,
            BigDecimal grossProfit,
            BigDecimal operatingExpenses,
            List<ExpenseCategory> operatingExpenseCategories,
            BigDecimal netIncome,
            ReportMetadata metadata
    ) {
    }
}
