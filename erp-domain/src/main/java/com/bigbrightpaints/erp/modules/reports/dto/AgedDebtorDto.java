package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record AgedDebtorDto(Long dealerId,
                             String dealerCode,
                             String dealerName,
                             BigDecimal current,
                             BigDecimal oneToThirtyDays,
                             BigDecimal thirtyOneToSixtyDays,
                             BigDecimal sixtyOneToNinetyDays,
                             BigDecimal ninetyPlusDays,
                             BigDecimal totalOutstanding,
                             ReportMetadata metadata,
                             ExportHints exportHints) {
    public AgedDebtorDto(String dealerName,
                         BigDecimal current,
                         BigDecimal thirtyDays,
                         BigDecimal sixtyDays,
                         BigDecimal ninetyDays) {
        this(null,
                null,
                dealerName,
                current,
                thirtyDays,
                sixtyDays,
                BigDecimal.ZERO,
                ninetyDays,
                safe(current).add(safe(thirtyDays)).add(safe(sixtyDays)).add(safe(ninetyDays)),
                null,
                new ExportHints(true, true, null));
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
