package com.bigbrightpaints.erp.modules.hr.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequestDto(Long id,
                              UUID publicId,
                              Long employeeId,
                              String employeeName,
                              String leaveType,
                              LocalDate startDate,
                              LocalDate endDate,
                              BigDecimal totalDays,
                              String status,
                              String reason,
                              String decisionReason,
                              String approvedBy,
                              Instant approvedAt,
                              String rejectedBy,
                              Instant rejectedAt,
                              Instant createdAt) {
}
