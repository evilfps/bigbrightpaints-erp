package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SalesTargetDto(Long id,
                             UUID publicId,
                             String name,
                             LocalDate periodStart,
                             LocalDate periodEnd,
                             BigDecimal targetAmount,
                             BigDecimal achievedAmount,
                             String assignee) {}
