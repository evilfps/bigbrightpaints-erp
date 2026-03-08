package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderDtoContractTest {

    @Test
    void record_exposesPaymentModeField() {
        SalesOrderDto dto = new SalesOrderDto(
                10L,
                java.util.UUID.randomUUID(),
                "SO-10",
                "CONFIRMED",
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                new BigDecimal("10.00"),
                new BigDecimal("18.00"),
                "NONE",
                false,
                BigDecimal.ZERO,
                "INR",
                "Dealer",
                "HYBRID",
                "trace-10",
                Instant.parse("2026-03-09T00:00:00Z"),
                List.of(),
                List.of());

        assertThat(dto.paymentMode()).isEqualTo("HYBRID");
    }
}
