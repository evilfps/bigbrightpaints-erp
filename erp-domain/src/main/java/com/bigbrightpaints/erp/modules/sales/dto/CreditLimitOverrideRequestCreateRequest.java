package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

public record CreditLimitOverrideRequestCreateRequest(
    @Schema(
            description =
                "Dealer identity for headroom evaluation; required when neither salesOrderId nor"
                    + " packagingSlipId can resolve the dealer")
        Long dealerId,
    Long packagingSlipId,
    Long salesOrderId,
    @Schema(
            description =
                "Canonical requested amount for temporary credit headroom. Required headroom is"
                    + " computed against outstanding + pending-order exposure")
        @Positive
        BigDecimal requestedAmount,
    @Schema(
            description = "Legacy alias of requestedAmount; retained for compatibility",
            deprecated = true)
        @Positive
        BigDecimal dispatchAmount,
    String reason,
    Instant expiresAt) {
  public CreditLimitOverrideRequestCreateRequest(
      Long dealerId,
      Long packagingSlipId,
      Long salesOrderId,
      BigDecimal dispatchAmount,
      String reason,
      Instant expiresAt) {
    this(dealerId, packagingSlipId, salesOrderId, null, dispatchAmount, reason, expiresAt);
  }
}
