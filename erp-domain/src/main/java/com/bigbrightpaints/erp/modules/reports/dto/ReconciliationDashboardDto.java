package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReconciliationDashboardDto(BigDecimal ledgerInventoryBalance,
                                         BigDecimal physicalInventoryValue,
                                         BigDecimal inventoryVariance,
                                         BigDecimal bankLedgerBalance,
                                         BigDecimal bankStatementBalance,
                                         BigDecimal bankVariance,
                                         boolean inventoryBalanced,
                                         boolean bankBalanced,
                                         List<BalanceWarningDto> balanceWarnings) {}

