package com.bigbrightpaints.erp.modules.hr.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaveTypePolicyDto(
        Long id,
        UUID publicId,
        String leaveType,
        String displayName,
        BigDecimal annualEntitlement,
        BigDecimal carryForwardLimit,
        boolean active
) {
}
