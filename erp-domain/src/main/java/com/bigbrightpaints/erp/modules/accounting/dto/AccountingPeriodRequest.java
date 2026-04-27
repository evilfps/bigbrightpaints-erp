package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.LocalDate;

import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AccountingPeriodRequest(
    @Min(1900) @Max(9999) Integer year,
    @Min(1) @Max(12) Integer month,
    LocalDate startDate,
    LocalDate endDate,
    CostingMethod costingMethod) {
  public AccountingPeriodRequest(
      Integer year, Integer month, LocalDate startDate, LocalDate endDate) {
    this(year, month, startDate, endDate, null);
  }

  public AccountingPeriodRequest(LocalDate startDate, LocalDate endDate) {
    this(null, null, startDate, endDate, null);
  }

  public AccountingPeriodRequest(CostingMethod costingMethod) {
    this(null, null, null, null, costingMethod);
  }
}
