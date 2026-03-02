package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeaveStatusUpdateRequest(
        @NotBlank String status,
        @Size(max = 2000) String decisionReason
) {
}
