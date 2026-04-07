package com.bigbrightpaints.erp.modules.sales.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SalesOrderStatusHistoryDto(
    Long id,
    String fromStatus,
    String toStatus,
    String reasonCode,
    String reason,
    String changedBy,
    Instant changedAt) {

  @JsonProperty("status")
  public String status() {
    return toStatus;
  }

  @JsonProperty("actor")
  public String actor() {
    return changedBy;
  }

  @JsonProperty("timestamp")
  public Instant timestamp() {
    return changedAt;
  }
}
