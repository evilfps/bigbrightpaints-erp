package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record SubledgerReconciliationDashboardDto(
    SubledgerControlSummary accountsReceivable,
    SubledgerControlSummary accountsPayable,
    BigDecimal difference,
    boolean balanced) {

  public record SubledgerControlSummary(
      BigDecimal controlBalance,
      BigDecimal subledgerTotal,
      BigDecimal difference,
      boolean balanced) {}
}
