package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record SupplierSettlementRequest(
    @NotNull Long supplierId,
    Long cashAccountId,
    Long discountAccountId,
    Long writeOffAccountId,
    Long fxGainAccountId,
    Long fxLossAccountId,
    @DecimalMin(value = "0.01") BigDecimal amount,
    @Schema(
            implementation = HeaderSettlementAllocationApplication.class,
            description =
                "Header-level unapplied amount handling. DOCUMENT is only valid on explicit"
                    + " allocation rows.")
        SettlementAllocationApplication unappliedAmountApplication,
    LocalDate settlementDate,
    String referenceNumber,
    String memo,
    @Schema(hidden = true)
    String idempotencyKey,
    Boolean adminOverride,
    List<@Valid SettlementAllocationRequest> allocations) {

  public SupplierSettlementRequest(
      Long supplierId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations) {
    this(
        supplierId,
        cashAccountId,
        discountAccountId,
        writeOffAccountId,
        fxGainAccountId,
        fxLossAccountId,
        null,
        null,
        settlementDate,
        referenceNumber,
        memo,
        idempotencyKey,
        adminOverride,
        allocations);
  }
}
