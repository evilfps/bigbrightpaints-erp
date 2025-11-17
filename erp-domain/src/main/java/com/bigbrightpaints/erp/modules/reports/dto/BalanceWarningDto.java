package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record BalanceWarningDto(Long accountId,
                                String accountCode,
                                String accountName,
                                BigDecimal balance,
                                String severity,
                                String reason) {}

