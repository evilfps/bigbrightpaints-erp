package com.bigbrightpaints.erp.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record PayrollRunRequest(
    @NotNull LocalDate payrollDate,
    @NotBlank String initiatedBy
) {}
