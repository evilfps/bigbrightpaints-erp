package com.bigbrightpaints.erp.modules.admin.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

public record AdminApprovalDecisionRequest(
    @NotNull Decision decision, String reason, Instant expiresAt) {

  public enum Decision {
    APPROVE,
    REJECT
  }
}
