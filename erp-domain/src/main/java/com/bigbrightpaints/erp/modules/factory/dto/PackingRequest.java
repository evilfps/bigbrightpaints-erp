package com.bigbrightpaints.erp.modules.factory.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PackingRequest(
    @NotNull(message = "Production log is required") Long productionLogId,
    LocalDate packedDate,
    String packedBy,
    @JsonIgnore
    @Schema(hidden = true)
    String idempotencyKey,
    @Valid @NotEmpty(message = "At least one packing line is required")
        List<PackingLineRequest> lines) {
  public PackingRequest(
      Long productionLogId, LocalDate packedDate, String packedBy, List<PackingLineRequest> lines) {
    this(productionLogId, packedDate, packedBy, null, lines);
  }
}
