package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

public record AdminApprovalsResponse(
        List<AdminApprovalItemDto> creditRequests,
        List<AdminApprovalItemDto> payrollRuns,
        List<AdminApprovalItemDto> exportRequests
) {}
