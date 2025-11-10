package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record PayrollRunRequest(
        @NotNull LocalDate runDate,
        String notes
) {}
