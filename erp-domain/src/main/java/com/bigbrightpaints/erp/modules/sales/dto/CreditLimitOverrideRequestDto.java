package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreditLimitOverrideRequestDto(
    Long id,
    UUID publicId,
    Long dealerId,
    String dealerName,
    Long packagingSlipId,
    Long salesOrderId,
    @Schema(description = "Canonical requested amount approved for temporary headroom")
        BigDecimal requestedAmount,
    @Schema(
            description = "Legacy alias of requestedAmount maintained for compatibility",
            deprecated = true)
        BigDecimal dispatchAmount,
    @Schema(
            description =
                "Dealer credit exposure snapshot at request time (outstanding ledger balance +"
                    + " pending-order exposure)")
        BigDecimal currentExposure,
    BigDecimal creditLimit,
    @Schema(
            description =
                "Temporary headroom required for approval under canonical order credit posture"
                    + " (outstanding + pending + requestedAmount - creditLimit)")
        BigDecimal requiredHeadroom,
    String status,
    String reason,
    String requestedBy,
    String reviewedBy,
    Instant reviewedAt,
    Instant expiresAt,
    Instant createdAt) {}
