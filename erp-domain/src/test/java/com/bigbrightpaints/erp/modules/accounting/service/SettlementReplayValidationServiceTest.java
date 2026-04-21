package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementReplayValidationServiceTest {

  private final SettlementReplayValidationService service = new SettlementReplayValidationService();

  @Test
  void roundedAmount_returnsZeroWhenAmountIsNull() {
    BigDecimal rounded =
        ReflectionTestUtils.invokeMethod(service, "roundedAmount", (BigDecimal) null);

    assertThat(rounded).isEqualByComparingTo("0.00");
  }

  @Test
  void roundedAmount_roundsUsingHalfUpScale() {
    BigDecimal rounded =
        ReflectionTestUtils.invokeMethod(service, "roundedAmount", new BigDecimal("10.005"));

    assertThat(rounded).isEqualByComparingTo("10.01");
  }
}
