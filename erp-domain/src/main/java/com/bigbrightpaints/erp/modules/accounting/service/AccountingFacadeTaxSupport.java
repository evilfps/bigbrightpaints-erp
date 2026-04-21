package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService.TaxAccountConfiguration;

@Service
final class AccountingFacadeTaxSupport {

  private final CompanyAccountingSettingsService companyAccountingSettingsService;

  AccountingFacadeTaxSupport(CompanyAccountingSettingsService companyAccountingSettingsService) {
    this.companyAccountingSettingsService = companyAccountingSettingsService;
  }

  void appendSalesTaxLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo,
      String orderNumber) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(taxLines, false);
      appendComponentCreditLines(
          lines, taxAccountId, resolvedMemo, orderNumber, gstBreakdown, "output");
      return;
    }
    if (taxLines == null) {
      return;
    }
    taxLines.forEach(
        (accountId, amount) -> {
          if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(
                new JournalEntryRequest.JournalLineRequest(
                    accountId, resolvedMemo, BigDecimal.ZERO, amount.abs()));
          }
        });
  }

  BigDecimal appendPurchaseTaxLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(taxLines, true);
      return appendComponentDebitLines(lines, taxAccountId, resolvedMemo, gstBreakdown, "input");
    }
    BigDecimal taxTotal = BigDecimal.ZERO;
    if (taxLines == null || taxLines.isEmpty()) {
      return taxTotal;
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    for (Map.Entry<Long, BigDecimal> entry : taxLines.entrySet()) {
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                entry.getKey() != null ? entry.getKey() : taxConfig.inputTaxAccountId(),
                "Input tax for " + resolvedMemo,
                amount.abs(),
                BigDecimal.ZERO));
        taxTotal = taxTotal.add(amount.abs());
      }
    }
    return taxTotal;
  }

  BigDecimal appendPurchaseReturnTaxLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxCredits,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(taxCredits, true);
      return appendComponentCreditLines(
          lines, taxAccountId, resolvedMemo, null, gstBreakdown, "reverse input");
    }
    BigDecimal taxTotal = BigDecimal.ZERO;
    if (taxCredits == null || taxCredits.isEmpty()) {
      return taxTotal;
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    for (Map.Entry<Long, BigDecimal> entry : taxCredits.entrySet()) {
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        Long accountId = entry.getKey() != null ? entry.getKey() : taxConfig.inputTaxAccountId();
        taxTotal = taxTotal.add(amount.abs());
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                accountId, "Reverse input tax for " + resolvedMemo, BigDecimal.ZERO, amount.abs()));
      }
    }
    return taxTotal;
  }

  JournalCreationRequest.GstBreakdown resolvePurchaseBreakdown(
      BigDecimal taxableAmount, BigDecimal taxTotal, JournalCreationRequest.GstBreakdown provided) {
    if (provided != null) {
      return provided;
    }
    if (taxTotal == null || taxTotal.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return new JournalCreationRequest.GstBreakdown(
        taxableAmount, BigDecimal.ZERO, BigDecimal.ZERO, taxTotal);
  }

  private BigDecimal appendComponentDebitLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Long taxAccountId,
      String resolvedMemo,
      JournalCreationRequest.GstBreakdown breakdown,
      String labelPrefix) {
    BigDecimal total = BigDecimal.ZERO;
    if (breakdown.cgst() != null && breakdown.cgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.cgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "CGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    if (breakdown.sgst() != null && breakdown.sgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.sgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "SGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    if (breakdown.igst() != null && breakdown.igst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.igst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "IGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    return total;
  }

  private BigDecimal appendComponentCreditLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Long taxAccountId,
      String resolvedMemo,
      String orderNumber,
      JournalCreationRequest.GstBreakdown breakdown,
      String labelPrefix) {
    BigDecimal total = BigDecimal.ZERO;
    String context = orderNumber != null && !orderNumber.isBlank() ? orderNumber : resolvedMemo;
    if (breakdown.cgst() != null && breakdown.cgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.cgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "CGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    if (breakdown.sgst() != null && breakdown.sgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.sgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "SGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    if (breakdown.igst() != null && breakdown.igst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.igst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "IGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    return total;
  }

  private Long resolveTaxAccountId(Map<Long, BigDecimal> taxLines, boolean inputTax) {
    if (taxLines != null) {
      Optional<Long> fromMap = taxLines.keySet().stream().filter(Objects::nonNull).findFirst();
      if (fromMap.isPresent()) {
        return fromMap.get();
      }
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    Long accountId = inputTax ? taxConfig.inputTaxAccountId() : taxConfig.outputTaxAccountId();
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          (inputTax ? "Input" : "Output") + " tax account is not configured");
    }
    return accountId;
  }

  private boolean breakdownHasTax(JournalCreationRequest.GstBreakdown breakdown) {
    if (breakdown == null) {
      return false;
    }
    BigDecimal cgst = breakdown.cgst() == null ? BigDecimal.ZERO : breakdown.cgst();
    BigDecimal sgst = breakdown.sgst() == null ? BigDecimal.ZERO : breakdown.sgst();
    BigDecimal igst = breakdown.igst() == null ? BigDecimal.ZERO : breakdown.igst();
    return cgst.compareTo(BigDecimal.ZERO) > 0
        || sgst.compareTo(BigDecimal.ZERO) > 0
        || igst.compareTo(BigDecimal.ZERO) > 0;
  }
}
