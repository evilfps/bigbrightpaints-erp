package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record ProfitLossDto(BigDecimal revenue,
                             BigDecimal costOfGoodsSold,
                             BigDecimal grossProfit,
                             BigDecimal operatingExpenses,
                             BigDecimal netIncome) {}
