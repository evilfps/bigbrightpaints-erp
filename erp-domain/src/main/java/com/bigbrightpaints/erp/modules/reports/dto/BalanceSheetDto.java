package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record BalanceSheetDto(BigDecimal totalAssets,
                              BigDecimal totalLiabilities,
                              BigDecimal totalEquity,
                              boolean balanced,
                              ReportMetadata metadata,
                              List<SectionLine> currentAssets,
                              List<SectionLine> fixedAssets,
                              List<SectionLine> currentLiabilities,
                              List<SectionLine> longTermLiabilities,
                              List<SectionLine> equityLines,
                              Comparative comparative) {

    public BalanceSheetDto(BigDecimal totalAssets,
                           BigDecimal totalLiabilities,
                           BigDecimal totalEquity,
                           ReportMetadata metadata) {
        this(totalAssets,
                totalLiabilities,
                totalEquity,
                totalAssets != null
                        && totalLiabilities != null
                        && totalEquity != null
                        && totalAssets.subtract(totalLiabilities.add(totalEquity)).abs().compareTo(new BigDecimal("0.01")) <= 0,
                metadata,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    public record SectionLine(
            Long accountId,
            String accountCode,
            String accountName,
            BigDecimal amount
    ) {
    }

    public record Comparative(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            boolean balanced,
            ReportMetadata metadata,
            List<SectionLine> currentAssets,
            List<SectionLine> fixedAssets,
            List<SectionLine> currentLiabilities,
            List<SectionLine> longTermLiabilities,
            List<SectionLine> equityLines
    ) {
    }
}
