package com.bigbrightpaints.erp.modules.hr.dto;

import java.math.BigDecimal;

public record MonthlyAttendanceSummaryDto(
        Long employeeId,
        String employeeName,
        String department,
        String designation,
        long presentDays,
        long halfDays,
        long absentDays,
        long leaveDays,
        long holidayDays,
        BigDecimal overtimeHours,
        BigDecimal doubleOvertimeHours
) {
}
