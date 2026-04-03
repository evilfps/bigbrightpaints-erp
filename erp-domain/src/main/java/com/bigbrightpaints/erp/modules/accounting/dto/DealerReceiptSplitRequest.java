package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Supports hybrid receipts: multiple incoming accounts (cash/bank/wallet) applied against dealer AR.
 */
public record DealerReceiptSplitRequest(
    @NotNull Long dealerId,
    @NotEmpty List<@Valid IncomingLine> incomingLines,
    String referenceNumber,
    String memo,
    @Schema(hidden = true)
    String idempotencyKey) {
  public record IncomingLine(
      @NotNull Long accountId, @NotNull @DecimalMin(value = "0.01") BigDecimal amount) {}
}
