package com.bigbrightpaints.erp.modules.factory.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PackingRequest(
    @NotNull(message = "Production log is required") Long productionLogId,
    LocalDate packedDate,
    String packedBy,
    @JsonIgnore @Schema(hidden = true) String idempotencyKey,
    @Valid List<PackingLineRequest> lines,
    @Schema(
            description =
                "Set true to close the remaining mixed quantity as canonical process loss on this"
                    + " packing request.")
        Boolean closeResidualWastage) {
  public PackingRequest(
      Long productionLogId, LocalDate packedDate, String packedBy, List<PackingLineRequest> lines) {
    this(productionLogId, packedDate, packedBy, null, lines, Boolean.FALSE);
  }

  public PackingRequest(
      Long productionLogId,
      LocalDate packedDate,
      String packedBy,
      String idempotencyKey,
      List<PackingLineRequest> lines) {
    this(productionLogId, packedDate, packedBy, idempotencyKey, lines, Boolean.FALSE);
  }

  public boolean closeResidualWastageRequested() {
    return Boolean.TRUE.equals(closeResidualWastage);
  }
}
