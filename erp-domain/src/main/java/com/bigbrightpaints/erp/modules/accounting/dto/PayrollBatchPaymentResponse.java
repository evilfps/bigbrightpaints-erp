package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollBatchPaymentResponse(
        Long payrollRunId,
        LocalDate runDate,
        BigDecimal totalAmount,
        Long journalEntryId,
        List<LineTotal> lines
) {
    public record LineTotal(String name,
                            Integer days,
                            BigDecimal dailyWage,
                            BigDecimal advances,
                            BigDecimal lineTotal,
                            String notes) {}
}
