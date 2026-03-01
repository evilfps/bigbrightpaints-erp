package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.time.Instant;

public record PurchaseOrderStatusHistoryResponse(
        Long id,
        String fromStatus,
        String toStatus,
        String reasonCode,
        String reason,
        String changedBy,
        Instant changedAt
) {
}
