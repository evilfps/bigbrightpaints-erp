package com.bigbrightpaints.erp.modules.admin.dto;

public record ExportRequestDownloadResponse(
        Long requestId,
        ExportApprovalStatus status,
        String reportType,
        String parameters,
        String message
) {
}
