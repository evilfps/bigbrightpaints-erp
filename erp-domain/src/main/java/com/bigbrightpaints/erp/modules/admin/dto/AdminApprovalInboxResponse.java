package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

public record AdminApprovalInboxResponse(List<AdminApprovalItemDto> items, long pendingCount) {}
