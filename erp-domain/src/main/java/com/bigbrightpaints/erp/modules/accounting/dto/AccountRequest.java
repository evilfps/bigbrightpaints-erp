package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String type
) {}
