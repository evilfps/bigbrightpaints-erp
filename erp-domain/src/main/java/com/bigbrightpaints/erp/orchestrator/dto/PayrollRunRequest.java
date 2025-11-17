package com.bigbrightpaints.erp.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollRunRequest(
    @NotNull LocalDate payrollDate,
    @NotBlank String initiatedBy,
    @NotNull Long debitAccountId,
    @NotNull Long creditAccountId,
    @NotNull BigDecimal postingAmount
) {}
