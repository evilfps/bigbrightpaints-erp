package com.bigbrightpaints.erp.modules.sales.dto;

import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record SalesOrderRequest(
        Long dealerId,
        @NotNull BigDecimal totalAmount,
        String currency,
        String notes,
        @NotEmpty List<@Valid SalesOrderItemRequest> items,
        String gstTreatment,
        BigDecimal gstRate,
        Boolean gstInclusive,
        String idempotencyKey,
        String paymentMode
) {
    private static final String DEFAULT_PAYMENT_MODE = "CREDIT";
    private static final String LEGACY_HYBRID_PAYMENT_MODE = "SPLIT";
    private static final String HYBRID_PAYMENT_MODE = "HYBRID";

    public SalesOrderRequest(
            Long dealerId,
            BigDecimal totalAmount,
            String currency,
            String notes,
            List<@Valid SalesOrderItemRequest> items,
            String gstTreatment,
            BigDecimal gstRate,
            Boolean gstInclusive,
            String idempotencyKey
    ) {
        this(dealerId, totalAmount, currency, notes, items, gstTreatment, gstRate, gstInclusive, idempotencyKey, null);
    }

    public String normalizedPaymentMode() {
        String normalized = rawNormalizedPaymentMode();
        if (DEFAULT_PAYMENT_MODE.equals(normalized)) {
            return DEFAULT_PAYMENT_MODE;
        }
        if (LEGACY_HYBRID_PAYMENT_MODE.equals(normalized)) {
            return HYBRID_PAYMENT_MODE;
        }
        return normalized;
    }

    public boolean usesLegacySplitReplayPaymentMode() {
        return LEGACY_HYBRID_PAYMENT_MODE.equals(rawNormalizedPaymentMode());
    }

    public String resolveIdempotencyKey() {
        String normalized = IdempotencyUtils.normalizeKey(idempotencyKey);
        if (normalized != null) {
            return normalized;
        }
        return resolveDerivedIdempotencyKey(normalizedPaymentMode(), false);
    }

    public String resolveIdempotencyKeyIncludingDefaultPaymentMode() {
        String normalized = IdempotencyUtils.normalizeKey(idempotencyKey);
        if (normalized != null) {
            return normalized;
        }
        return resolveDerivedIdempotencyKey(normalizedPaymentMode(), true);
    }

    public String resolveLegacySplitReplayIdempotencyKey() {
        String normalized = IdempotencyUtils.normalizeKey(idempotencyKey);
        if (normalized != null || !usesLegacySplitReplayPaymentMode()) {
            return null;
        }
        return resolveDerivedIdempotencyKey(LEGACY_HYBRID_PAYMENT_MODE, false);
    }

    private String resolveDerivedIdempotencyKey(String normalizedPaymentMode,
                                                boolean includeDefaultPaymentModeSegment) {
        IdempotencySignatureBuilder signatureBuilder = IdempotencySignatureBuilder.create()
                .add(dealerId == null ? "null" : dealerId)
                .add(totalAmount)
                .addUpperToken(currency);
        if (includeDefaultPaymentModeSegment || !DEFAULT_PAYMENT_MODE.equals(normalizedPaymentMode)) {
            signatureBuilder.add(normalizedPaymentMode);
        }
        if (items != null) {
            for (SalesOrderItemRequest item : items) {
                signatureBuilder.add(IdempotencyUtils.normalizeUpperToken(item.productCode())
                        + ':' + item.quantity()
                        + ':' + item.unitPrice());
            }
        }
        return signatureBuilder.buildHash();
    }

    private String rawNormalizedPaymentMode() {
        String normalized = IdempotencyUtils.normalizeUpperToken(paymentMode);
        if (normalized.isBlank()) {
            return DEFAULT_PAYMENT_MODE;
        }
        return normalized;
    }
}
