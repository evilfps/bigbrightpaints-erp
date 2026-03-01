package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.constraints.NotBlank;

public record PurchaseOrderVoidRequest(
        @NotBlank String reasonCode,
        String reason
) {
}
