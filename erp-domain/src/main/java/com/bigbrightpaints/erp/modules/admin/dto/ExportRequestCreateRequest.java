package com.bigbrightpaints.erp.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record ExportRequestCreateRequest(
    @NotBlank String reportType, String format, String parameters) {}
