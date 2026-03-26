package com.bigbrightpaints.erp.modules.company.dto;

public record MainAdminSummaryDto(
    Long userId, String email, String displayName, boolean enabled, boolean replaceable) {}
