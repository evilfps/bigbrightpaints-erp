package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record BalanceSheetDto(BigDecimal totalAssets,
                              BigDecimal totalLiabilities,
                              BigDecimal totalEquity) {}
