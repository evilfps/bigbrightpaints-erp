package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
class JournalReferenceService {

  private static final BigDecimal FX_RATE_MIN = new BigDecimal("0.0001");
  private static final BigDecimal FX_RATE_MAX = new BigDecimal("100000");

  private final ReferenceNumberService referenceNumberService;

  JournalReferenceService(ReferenceNumberService referenceNumberService) {
    this.referenceNumberService = referenceNumberService;
  }

  String resolveJournalReference(Company company, String provided) {
    if (provided != null) {
      String normalizedProvided = provided.trim();
      if (!normalizedProvided.isEmpty()) {
        return normalizedProvided;
      }
    }
    return referenceNumberService.nextJournalReference(company);
  }

  String resolveCurrency(String requested, Company company) {
    String base =
        company != null && StringUtils.hasText(company.getBaseCurrency())
            ? company.getBaseCurrency().trim().toUpperCase(Locale.ROOT)
            : "INR";
    if (!StringUtils.hasText(requested)) {
      return base;
    }
    return requested.trim().toUpperCase(Locale.ROOT);
  }

  BigDecimal resolveFxRate(String currency, Company company, BigDecimal requestedRate) {
    String base =
        company != null && StringUtils.hasText(company.getBaseCurrency())
            ? company.getBaseCurrency().trim().toUpperCase(Locale.ROOT)
            : "INR";
    if (!StringUtils.hasText(currency) || currency.equalsIgnoreCase(base)) {
      return BigDecimal.ONE;
    }
    if (requestedRate == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "FX rate is required for currency " + currency);
    }
    BigDecimal rate = requestedRate;
    if (rate.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "FX rate must be positive");
    }
    if (rate.compareTo(FX_RATE_MIN) < 0 || rate.compareTo(FX_RATE_MAX) > 0) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "FX rate out of bounds")
          .withDetail("min", FX_RATE_MIN)
          .withDetail("max", FX_RATE_MAX)
          .withDetail("requested", rate);
    }
    return rate.setScale(6, RoundingMode.HALF_UP);
  }

  BigDecimal toBaseCurrency(BigDecimal amount, BigDecimal fxRate) {
    if (amount == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal rate = fxRate == null ? BigDecimal.ONE : fxRate;
    return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  String joinAttachmentReferences(List<String> attachmentReferences) {
    if (attachmentReferences == null || attachmentReferences.isEmpty()) {
      return null;
    }
    return attachmentReferences.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .collect(Collectors.joining(","));
  }

  String resolvePostingDocumentType(JournalEntry entry) {
    if (entry == null || !StringUtils.hasText(entry.getSourceModule())) {
      return "JOURNAL_ENTRY";
    }
    return entry.getSourceModule().trim().toUpperCase(Locale.ROOT);
  }

  String resolvePostingDocumentReference(JournalEntry entry) {
    if (entry == null) {
      return null;
    }
    if (StringUtils.hasText(entry.getSourceReference())) {
      return entry.getSourceReference().trim();
    }
    if (StringUtils.hasText(entry.getReferenceNumber())) {
      return entry.getReferenceNumber().trim();
    }
    return null;
  }
}
