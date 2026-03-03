package com.bigbrightpaints.erp.modules.admin.dto;

import java.time.Instant;

public record ExportRequestDto(
        Long id,
        Long userId,
        String userEmail,
        String reportType,
        String parameters,
        ExportApprovalStatus status,
        String rejectionReason,
        Instant createdAt,
        String approvedBy,
        Instant approvedAt
) {
}
