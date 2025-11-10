package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record CashFlowDto(BigDecimal operating,
                           BigDecimal investing,
                           BigDecimal financing,
                           BigDecimal netChange) {}
