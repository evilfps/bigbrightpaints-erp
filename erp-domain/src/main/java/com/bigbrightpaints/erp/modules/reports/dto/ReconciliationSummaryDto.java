package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record ReconciliationSummaryDto(BigDecimal physicalInventoryValue,
                                       BigDecimal ledgerInventoryBalance,
                                       BigDecimal variance) {}
