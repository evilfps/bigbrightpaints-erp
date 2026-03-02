package com.bigbrightpaints.erp.modules.hr.dto;

import java.math.BigDecimal;

public record LeaveBalanceDto(
        Long employeeId,
        String leaveType,
        Integer year,
        BigDecimal openingBalance,
        BigDecimal accrued,
        BigDecimal used,
        BigDecimal remaining,
        BigDecimal carryForwardApplied
) {
}
