package com.bigbrightpaints.erp.modules.hr.dto;

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
                              String status,
                              String reason,
                              Instant createdAt) {}
