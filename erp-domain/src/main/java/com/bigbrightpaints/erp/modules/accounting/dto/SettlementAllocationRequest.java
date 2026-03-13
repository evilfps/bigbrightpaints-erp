package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SettlementAllocationRequest(
        Long invoiceId,
        Long purchaseId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal appliedAmount,
        @DecimalMin(value = "0.00") BigDecimal discountAmount,
        @DecimalMin(value = "0.00") BigDecimal writeOffAmount,
        BigDecimal fxAdjustment,
        SettlementAllocationApplication applicationType,
        String memo
) {

    public SettlementAllocationRequest(Long invoiceId,
                                      Long purchaseId,
                                      BigDecimal appliedAmount,
                                      BigDecimal discountAmount,
                                      BigDecimal writeOffAmount,
                                      BigDecimal fxAdjustment,
                                      String memo) {
        this(invoiceId, purchaseId, appliedAmount, discountAmount, writeOffAmount, fxAdjustment, null, memo);
    }
}
