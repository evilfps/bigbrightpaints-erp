package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;

@Service
class SettlementTotalsValidationService {

  private static final String SETTLEMENT_APPLICATION_PREFIX = "[SETTLEMENT-APPLICATION:";
  private static final BigDecimal ALLOCATION_TOLERANCE = new BigDecimal("0.01");

  BigDecimal normalizeNonNegative(BigDecimal value, String field) {
    BigDecimal normalized = MoneyUtils.zeroIfNull(value);
    if (normalized.compareTo(BigDecimal.ZERO) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Value for " + field + " cannot be negative");
    }
    return normalized;
  }

  void validatePaymentAllocations(
      List<SettlementAllocationRequest> allocations,
      BigDecimal amount,
      String label,
      boolean dealer) {
    if (allocations == null || allocations.isEmpty()) {
      return;
    }
    BigDecimal totalApplied = BigDecimal.ZERO;
    for (SettlementAllocationRequest allocation : allocations) {
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      SettlementAllocationApplication applicationType =
          resolveSettlementApplicationType(allocation);
      if (applicationType.isUnapplied()
          && (discount.compareTo(BigDecimal.ZERO) > 0
              || writeOff.compareTo(BigDecimal.ZERO) > 0
              || fxAdjustment.compareTo(BigDecimal.ZERO) != 0)) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Discount/write-off/FX adjustments are not supported for " + label + " allocations");
      }
      if (dealer && allocation.invoiceId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Invoice allocation is required for dealer settlements");
      }
      totalApplied = totalApplied.add(applied);
    }
    if (totalApplied.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Allocation total must equal payment amount")
          .withDetail("allocationTotal", totalApplied)
          .withDetail("paymentAmount", amount);
    }
  }

  void validateDealerSettlementAllocations(List<SettlementAllocationRequest> allocations) {
    if (allocations == null) {
      return;
    }
    Set<Long> seenInvoiceIds = new HashSet<>();
    Set<SettlementAllocationApplication> seenUnappliedApplications = new HashSet<>();
    for (SettlementAllocationRequest allocation : allocations) {
      SettlementAllocationApplication applicationType =
          resolveSettlementApplicationType(allocation);
      if (applicationType.isUnapplied()) {
        if (allocation.invoiceId() != null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Unapplied dealer settlement rows cannot reference an invoice");
        }
        if (!seenUnappliedApplications.add(applicationType)) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Dealer settlements cannot include duplicate unapplied allocation rows");
        }
      } else {
        if (allocation.invoiceId() == null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Invoice allocation is required for dealer settlements");
        }
        if (allocation.purchaseId() != null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Dealer settlements cannot allocate to purchases");
        }
        if (!seenInvoiceIds.add(allocation.invoiceId())) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT,
                  "Dealer settlements cannot include duplicate invoice allocations")
              .withDetail("invoiceId", allocation.invoiceId());
        }
      }
      normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
    }
  }

  void validateSupplierSettlementAllocations(List<SettlementAllocationRequest> allocations) {
    if (allocations == null) {
      return;
    }
    Set<Long> seenPurchaseIds = new HashSet<>();
    Set<SettlementAllocationApplication> seenUnappliedApplications = new HashSet<>();
    for (SettlementAllocationRequest allocation : allocations) {
      if (allocation.invoiceId() != null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Supplier settlements cannot allocate to invoices");
      }
      SettlementAllocationApplication applicationType =
          resolveSettlementApplicationType(allocation);
      if (applicationType.isUnapplied()) {
        if (allocation.purchaseId() != null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Unapplied supplier settlement rows cannot reference a purchase");
        }
        if (!seenUnappliedApplications.add(applicationType)) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Supplier settlements cannot include duplicate unapplied allocation rows");
        }
      } else if (allocation.purchaseId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Purchase allocation is required for supplier settlements unless unapplied");
      } else if (!seenPurchaseIds.add(allocation.purchaseId())) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Supplier settlements cannot include duplicate purchase allocations")
            .withDetail("purchaseId", allocation.purchaseId());
      }
      normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
    }
  }

  SettlementTotals computeSettlementTotals(List<SettlementAllocationRequest> allocations) {
    BigDecimal totalApplied = BigDecimal.ZERO;
    BigDecimal totalDiscount = BigDecimal.ZERO;
    BigDecimal totalWriteOff = BigDecimal.ZERO;
    BigDecimal totalFxGain = BigDecimal.ZERO;
    BigDecimal totalFxLoss = BigDecimal.ZERO;
    if (allocations == null) {
      return new SettlementTotals(
          totalApplied, totalDiscount, totalWriteOff, totalFxGain, totalFxLoss);
    }
    for (SettlementAllocationRequest allocation : allocations) {
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      totalApplied = totalApplied.add(applied);
      totalDiscount = totalDiscount.add(discount);
      totalWriteOff = totalWriteOff.add(writeOff);
      if (fxAdjustment.compareTo(BigDecimal.ZERO) > 0) {
        totalFxGain = totalFxGain.add(fxAdjustment);
      } else if (fxAdjustment.compareTo(BigDecimal.ZERO) < 0) {
        totalFxLoss = totalFxLoss.add(fxAdjustment.abs());
      }
    }
    return new SettlementTotals(
        totalApplied, totalDiscount, totalWriteOff, totalFxGain, totalFxLoss);
  }

  SettlementAllocationApplication resolveSettlementApplicationType(
      SettlementAllocationRequest allocation) {
    if (allocation == null) {
      return SettlementAllocationApplication.DOCUMENT;
    }
    if (allocation.applicationType() != null) {
      return allocation.applicationType();
    }
    if (allocation.invoiceId() == null && allocation.purchaseId() == null) {
      return SettlementAllocationApplication.ON_ACCOUNT;
    }
    return SettlementAllocationApplication.DOCUMENT;
  }

  String encodeSettlementAllocationMemo(
      SettlementAllocationApplication applicationType, String memo) {
    SettlementAllocationApplication resolved =
        applicationType != null ? applicationType : SettlementAllocationApplication.DOCUMENT;
    String visibleMemo = memo != null && !memo.isBlank() ? memo.trim() : null;
    if (!resolved.isUnapplied()) {
      return visibleMemo;
    }
    String prefix = SETTLEMENT_APPLICATION_PREFIX + resolved.name() + "]";
    return visibleMemo != null ? prefix + " " + visibleMemo : prefix;
  }

  boolean settlementOverrideRequested(SettlementTotals totals) {
    if (totals == null) {
      return false;
    }
    return totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0
        || totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0
        || totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0
        || totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0;
  }
}
