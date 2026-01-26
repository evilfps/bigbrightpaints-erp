package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkPackingServiceTest {

    @Test
    void parseSizeInLitersSupportsMlAndLtr() {
        assertThat(BulkPackingService.parseSizeInLiters("500ML"))
                .isEqualByComparingTo(new BigDecimal("0.500000"));
        assertThat(BulkPackingService.parseSizeInLiters("1LTR"))
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(BulkPackingService.parseSizeInLiters("0.5L"))
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void parseSizeInLitersReturnsNullForInvalid() {
        assertThat(BulkPackingService.parseSizeInLiters("SIZE"))
                .isNull();
    }
}
