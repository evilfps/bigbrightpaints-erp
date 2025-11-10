package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record AgedDebtorDto(String dealerName,
                             BigDecimal current,
                             BigDecimal thirtyDays,
                             BigDecimal sixtyDays,
                             BigDecimal ninetyDays) {}
