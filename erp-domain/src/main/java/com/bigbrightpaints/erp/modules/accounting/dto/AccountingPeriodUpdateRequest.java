package com.bigbrightpaints.erp.modules.accounting.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import jakarta.validation.constraints.NotNull;

public record AccountingPeriodUpdateRequest(@NotNull CostingMethod costingMethod) {}
