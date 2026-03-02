package com.bigbrightpaints.erp.modules.hr.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SalaryStructureTemplateDto(
        Long id,
        UUID publicId,
        String code,
        String name,
        String description,
        BigDecimal basicPay,
        BigDecimal hra,
        BigDecimal da,
        BigDecimal specialAllowance,
        BigDecimal totalEarnings,
        BigDecimal employeePfRate,
        BigDecimal employeeEsiRate,
        boolean active,
        Instant createdAt
) {
}
