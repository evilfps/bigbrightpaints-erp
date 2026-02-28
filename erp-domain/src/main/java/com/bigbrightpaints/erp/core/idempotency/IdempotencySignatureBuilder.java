package com.bigbrightpaints.erp.core.idempotency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class IdempotencySignatureBuilder {

    private final List<String> segments = new ArrayList<>();

    private IdempotencySignatureBuilder() {
    }

    public static IdempotencySignatureBuilder create() {
        return new IdempotencySignatureBuilder();
    }

    public IdempotencySignatureBuilder add(Object value) {
        segments.add(value != null ? String.valueOf(value) : "");
        return this;
    }

    public IdempotencySignatureBuilder addToken(String value) {
        segments.add(IdempotencyUtils.normalizeToken(value));
        return this;
    }

    public IdempotencySignatureBuilder addUpperToken(String value) {
        segments.add(IdempotencyUtils.normalizeUpperToken(value));
        return this;
    }

    public IdempotencySignatureBuilder addAmount(BigDecimal value) {
        segments.add(IdempotencyUtils.normalizeAmount(value));
        return this;
    }

    public IdempotencySignatureBuilder addDecimal(BigDecimal value) {
        segments.add(IdempotencyUtils.normalizeDecimal(value));
        return this;
    }

    public String buildPayload() {
        return String.join("|", segments);
    }

    public String buildHash() {
        return IdempotencyUtils.sha256Hex(buildPayload());
    }

    public String buildHash(int length) {
        return IdempotencyUtils.sha256Hex(buildPayload(), length);
    }
}
