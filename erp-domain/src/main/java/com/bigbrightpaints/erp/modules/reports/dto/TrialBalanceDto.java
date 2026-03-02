package com.bigbrightpaints.erp.modules.reports.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;

import java.math.BigDecimal;
import java.util.List;

public record TrialBalanceDto(
        List<Row> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced,
        ReportMetadata metadata,
        Comparative comparative
) {
    public TrialBalanceDto(List<Row> rows,
                           BigDecimal totalDebit,
                           BigDecimal totalCredit,
                           boolean balanced,
                           ReportMetadata metadata) {
        this(rows, totalDebit, totalCredit, balanced, metadata, null);
    }

    public record Row(
            Long accountId,
            String code,
            String name,
            AccountType type,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal net
    ) {
        public Row(Long accountId,
                   String code,
                   String name,
                   AccountType type,
                   BigDecimal debit,
                   BigDecimal credit) {
            this(accountId, code, name, type, debit, credit,
                    (debit == null ? BigDecimal.ZERO : debit)
                            .subtract(credit == null ? BigDecimal.ZERO : credit));
        }
    }

    public record Comparative(
            List<Row> rows,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            boolean balanced,
            ReportMetadata metadata
    ) {
    }
}
