package com.bigbrightpaints.erp.core.auditaccess;

import java.util.List;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;

record AuditFeedSlice(List<AuditFeedItemDto> items, long totalElements) {}
