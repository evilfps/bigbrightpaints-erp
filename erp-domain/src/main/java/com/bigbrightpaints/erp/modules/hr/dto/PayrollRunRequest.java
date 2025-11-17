package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollRunRequest(
        @NotNull LocalDate runDate,
        @DecimalMin(value = "0.00") BigDecimal totalAmount,
        String notes
) {}
