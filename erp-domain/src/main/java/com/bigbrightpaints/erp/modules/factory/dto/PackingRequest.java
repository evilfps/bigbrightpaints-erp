package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record PackingRequest(
        @NotNull(message = "Production log is required")
        Long productionLogId,
        LocalDate packedDate,
        String packedBy,
        @Valid
        @NotEmpty(message = "At least one packing line is required")
        List<PackingLineRequest> lines
) {}
