package com.bigbrightpaints.erp.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;

public record DispatchRequest(
    @NotBlank String batchId,
    @NotBlank String requestedBy
) {}
