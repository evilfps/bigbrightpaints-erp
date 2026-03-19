package com.bigbrightpaints.erp.modules.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminApprovalItemDto(
        OriginType originType,
        OwnerType ownerType,
        Long id,
        UUID publicId,
        String reference,
        String status,
        String summary,
        String reportType,
        String parameters,
        Long requesterUserId,
        String requesterEmail,
        String actionType,
        String actionLabel,
        String approveEndpoint,
        String rejectEndpoint,
        Instant createdAt
) {
    public enum OriginType {
        CREDIT_REQUEST,
        CREDIT_LIMIT_OVERRIDE_REQUEST,
        PAYROLL_RUN,
        PERIOD_CLOSE_REQUEST,
        EXPORT_REQUEST
    }

    public enum OwnerType {
        SALES,
        FACTORY,
        HR,
        ACCOUNTING,
        REPORTS
    }
}
