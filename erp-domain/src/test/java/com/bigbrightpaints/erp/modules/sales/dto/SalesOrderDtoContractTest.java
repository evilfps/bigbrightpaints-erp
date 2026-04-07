package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class SalesOrderDtoContractTest {

  @Test
  void record_exposesPaymentModeAndTermsFields() {
    SalesOrderDto dto =
        new SalesOrderDto(
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
            "CUSTOM_120",
            "trace-10",
            Instant.parse("2026-03-09T00:00:00Z"),
            List.of(),
            List.of());

    assertThat(dto.paymentMode()).isEqualTo("HYBRID");
    assertThat(dto.paymentTerms()).isEqualTo("CUSTOM_120");
  }
}
