package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class SalesOrderRequestTest {

    private SalesOrderRequest requestWithIdempotency(String key, String currency, String productCode, BigDecimal quantity) {
        SalesOrderItemRequest item = new SalesOrderItemRequest(productCode, "Item", quantity, new BigDecimal("10"), null);
        return new SalesOrderRequest(1L, new BigDecimal("100"), currency, null, List.of(item),
                "NONE", BigDecimal.ZERO, false, key);
    }

    @Test
    void resolveIdempotencyKey_prefersExplicitKeyTrimmed() {
        SalesOrderRequest request = requestWithIdempotency("  KEY-123  ", "INR", "FG-1", new BigDecimal("2"));
        assertThat(request.resolveIdempotencyKey()).isEqualTo("KEY-123");
    }

    @Test
    void resolveIdempotencyKey_blankKey_usesHash() {
        SalesOrderRequest request = requestWithIdempotency(" ", "INR", "FG-1", new BigDecimal("2"));
        assertThat(request.resolveIdempotencyKey()).isNotBlank();
        assertThat(request.resolveIdempotencyKey()).hasSize(64);
    }

    @Test
    void resolveIdempotencyKey_sameInput_sameHash() {
        SalesOrderRequest one = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("2"));
        SalesOrderRequest two = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("2"));
        assertThat(one.resolveIdempotencyKey()).isEqualTo(two.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKey_currencyNormalized() {
        SalesOrderRequest one = requestWithIdempotency(null, "inr", "FG-1", new BigDecimal("2"));
        SalesOrderRequest two = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("2"));
        assertThat(one.resolveIdempotencyKey()).isEqualTo(two.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKey_productCodeNormalized() {
        SalesOrderRequest one = requestWithIdempotency(null, "INR", " fg-1 ", new BigDecimal("2"));
        SalesOrderRequest two = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("2"));
        assertThat(one.resolveIdempotencyKey()).isEqualTo(two.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKey_quantityChange_changesHash() {
        SalesOrderRequest one = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("2"));
        SalesOrderRequest two = requestWithIdempotency(null, "INR", "FG-1", new BigDecimal("3"));
        assertThat(one.resolveIdempotencyKey()).isNotEqualTo(two.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKey_nullDealerId_stable() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("1"), new BigDecimal("10"), null);
        SalesOrderRequest one = new SalesOrderRequest(null, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null);
        SalesOrderRequest two = new SalesOrderRequest(null, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null);
        assertThat(one.resolveIdempotencyKey()).isEqualTo(two.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKey_defaultCreditMode_matchesLegacyFormat() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("2"), new BigDecimal("10"), null);
        SalesOrderRequest request = new SalesOrderRequest(7L, new BigDecimal("100"), "inr", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, "CREDIT");

        String legacyPayload = "7|100|INR|FG-1:2:10";
        assertThat(request.resolveIdempotencyKey()).isEqualTo(DigestUtils.sha256Hex(legacyPayload));
    }

    @Test
    void resolveIdempotencyKey_nonDefaultPaymentMode_changesHash() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("2"), new BigDecimal("10"), null);
        SalesOrderRequest defaultCredit = new SalesOrderRequest(7L, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, null);
        SalesOrderRequest cash = new SalesOrderRequest(7L, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, "cash");

        assertThat(cash.resolveIdempotencyKey()).isNotEqualTo(defaultCredit.resolveIdempotencyKey());
    }

    @Test
    void resolveIdempotencyKeyIncludingDefaultPaymentMode_keepsTransitionalDefaultCreditShape() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("2"), new BigDecimal("10"), null);
        SalesOrderRequest request = new SalesOrderRequest(7L, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, "CREDIT");

        String transitionalPayload = "7|100|INR|CREDIT|FG-1:2:10";
        assertThat(request.resolveIdempotencyKeyIncludingDefaultPaymentMode())
                .isEqualTo(DigestUtils.sha256Hex(transitionalPayload));
    }

    @Test
    void constructor_defaultsBlankPaymentModeToCredit() {
        SalesOrderRequest request = requestWithIdempotency("key-default", "INR", "FG-1", BigDecimal.ONE);

        assertThat(request.normalizedPaymentMode()).isEqualTo("CREDIT");
    }

    @Test
    void constructor_mapsLegacySplitPaymentModeToHybrid() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("2"), new BigDecimal("10"), null);
        SalesOrderRequest request = new SalesOrderRequest(7L, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, "split");

        assertThat(request.normalizedPaymentMode()).isEqualTo("HYBRID");
    }

    @Test
    void resolveLegacySplitReplayIdempotencyKey_preservesLegacySplitShape() {
        SalesOrderItemRequest item = new SalesOrderItemRequest("FG-1", "Item", new BigDecimal("2"), new BigDecimal("10"), null);
        SalesOrderRequest request = new SalesOrderRequest(7L, new BigDecimal("100"), "INR", null, List.of(item),
                "NONE", BigDecimal.ZERO, false, null, "split");

        assertThat(request.resolveIdempotencyKey()).isEqualTo(DigestUtils.sha256Hex("7|100|INR|HYBRID|FG-1:2:10"));
        assertThat(request.resolveLegacySplitReplayIdempotencyKey())
                .isEqualTo(DigestUtils.sha256Hex("7|100|INR|SPLIT|FG-1:2:10"));
    }
}
