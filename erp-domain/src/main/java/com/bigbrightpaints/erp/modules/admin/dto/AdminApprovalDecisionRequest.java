package com.bigbrightpaints.erp.modules.admin.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminApprovalDecisionRequest(
    @NotNull
        @Schema(
            description = "Decision action.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"APPROVE", "REJECT"})
        Decision decision,
    @Schema(
            description =
                "Decision reason. Required and nonblank for CREDIT_REQUEST, "
                    + "CREDIT_LIMIT_OVERRIDE_REQUEST, and PERIOD_CLOSE_REQUEST. Optional for "
                    + "EXPORT_REQUEST and ignored for PAYROLL_RUN approve.")
        String reason,
    @Schema(description = "Optional expiry timestamp for CREDIT_LIMIT_OVERRIDE_REQUEST approvals.")
        Instant expiresAt) {

  public enum Decision {
    APPROVE,
    REJECT
  }
}
