package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record PartnerSettlementRequest(
    PartnerType partnerType,
    @NotNull @JsonAlias({"dealerId", "supplierId"}) Long partnerId,
    Long cashAccountId,
    Long discountAccountId,
    Long writeOffAccountId,
    Long fxGainAccountId,
    Long fxLossAccountId,
    @DecimalMin(value = "0.01") BigDecimal amount,
    @Schema(
            implementation = SettlementAllocationApplication.class,
            description =
                "Header-level unapplied amount handling. DOCUMENT is only valid on explicit"
                    + " allocation rows.")
        SettlementAllocationApplication unappliedAmountApplication,
    @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
    String referenceNumber,
    String memo,
    String idempotencyKey,
    Boolean adminOverride,
    List<@Valid SettlementAllocationRequest> allocations,
    List<@Valid SettlementPaymentRequest> payments) {

  public PartnerSettlementRequest(
      PartnerType partnerType,
      Long partnerId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations,
      List<@Valid SettlementPaymentRequest> payments) {
    this(
        partnerType,
        partnerId,
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
        allocations,
        payments);
  }

  public PartnerSettlementRequest(
      Long dealerId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      BigDecimal amount,
      SettlementAllocationApplication unappliedAmountApplication,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations,
      List<@Valid SettlementPaymentRequest> payments) {
    this(
        PartnerType.DEALER,
        dealerId,
        cashAccountId,
        discountAccountId,
        writeOffAccountId,
        fxGainAccountId,
        fxLossAccountId,
        amount,
        unappliedAmountApplication,
        settlementDate,
        referenceNumber,
        memo,
        idempotencyKey,
        adminOverride,
        allocations,
        payments);
  }

  public PartnerSettlementRequest(
      Long dealerId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations,
      List<@Valid SettlementPaymentRequest> payments) {
    this(
        PartnerType.DEALER,
        dealerId,
        cashAccountId,
        discountAccountId,
        writeOffAccountId,
        fxGainAccountId,
        fxLossAccountId,
        settlementDate,
        referenceNumber,
        memo,
        idempotencyKey,
        adminOverride,
        allocations,
        payments);
  }

  public PartnerSettlementRequest(
      Long supplierId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      BigDecimal amount,
      SettlementAllocationApplication unappliedAmountApplication,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations) {
    this(
        PartnerType.SUPPLIER,
        supplierId,
        cashAccountId,
        discountAccountId,
        writeOffAccountId,
        fxGainAccountId,
        fxLossAccountId,
        amount,
        unappliedAmountApplication,
        settlementDate,
        referenceNumber,
        memo,
        idempotencyKey,
        adminOverride,
        allocations,
        null);
  }

  public PartnerSettlementRequest(
      Long supplierId,
      Long cashAccountId,
      Long discountAccountId,
      Long writeOffAccountId,
      Long fxGainAccountId,
      Long fxLossAccountId,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate settlementDate,
      String referenceNumber,
      String memo,
      String idempotencyKey,
      Boolean adminOverride,
      List<@Valid SettlementAllocationRequest> allocations) {
    this(
        PartnerType.SUPPLIER,
        supplierId,
        cashAccountId,
        discountAccountId,
        writeOffAccountId,
        fxGainAccountId,
        fxLossAccountId,
        settlementDate,
        referenceNumber,
        memo,
        idempotencyKey,
        adminOverride,
        allocations,
        null);
  }

  public Long dealerId() {
    return partnerType == PartnerType.DEALER ? partnerId : null;
  }

  public Long supplierId() {
    return partnerType == PartnerType.SUPPLIER ? partnerId : null;
  }
}
