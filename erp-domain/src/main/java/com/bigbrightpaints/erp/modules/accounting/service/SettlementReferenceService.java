package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

@Service
class SettlementReferenceService {

  private final ReferenceNumberService referenceNumberService;
  private final JournalReplayService journalReplayService;

  SettlementReferenceService(
      ReferenceNumberService referenceNumberService, JournalReplayService journalReplayService) {
    this.referenceNumberService = referenceNumberService;
    this.journalReplayService = journalReplayService;
  }

  String resolveReceiptIdempotencyKey(String provided, String reference, String label) {
    if (StringUtils.hasText(provided)) {
      return provided.trim();
    }
    if (StringUtils.hasText(reference)) {
      return reference.trim();
    }
    throw new ApplicationException(
        ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
        "Idempotency key or reference number is required for " + label);
  }

  String resolveDealerSettlementIdempotencyKey(PartnerSettlementRequest request) {
    if (request == null) {
      return "";
    }
    if (StringUtils.hasText(request.idempotencyKey())) {
      return request.idempotencyKey().trim();
    }
    if (StringUtils.hasText(request.referenceNumber())) {
      return request.referenceNumber().trim();
    }
    return buildDealerSettlementIdempotencyKey(request);
  }

  String resolveExplicitSettlementReplayKey(PartnerSettlementRequest request) {
    if (request == null) {
      return null;
    }
    if (StringUtils.hasText(request.idempotencyKey())) {
      return request.idempotencyKey().trim();
    }
    if (StringUtils.hasText(request.referenceNumber())) {
      return request.referenceNumber().trim();
    }
    return null;
  }

  String resolveSupplierSettlementIdempotencyKey(PartnerSettlementRequest request) {
    if (request == null) {
      return "";
    }
    if (StringUtils.hasText(request.idempotencyKey())) {
      return request.idempotencyKey().trim();
    }
    if (StringUtils.hasText(request.referenceNumber())) {
      return request.referenceNumber().trim();
    }
    return buildSupplierSettlementIdempotencyKey(request);
  }

  String resolveDealerSettlementReference(
      Company company, Dealer dealer, PartnerSettlementRequest request, String idempotencyKey) {
    if (request != null && StringUtils.hasText(request.referenceNumber())) {
      return request.referenceNumber().trim();
    }
    if (company != null && StringUtils.hasText(idempotencyKey)) {
      String key = journalReplayService.normalizeIdempotencyMappingKey(idempotencyKey);
      JournalReferenceMapping mapping =
          journalReplayService.findLatestLegacyReferenceMapping(company, key).orElse(null);
      if (mapping != null && StringUtils.hasText(mapping.getCanonicalReference())) {
        return mapping.getCanonicalReference().trim();
      }
      return journalReplayService.reservedManualReference(key);
    }
    return referenceNumberService.dealerReceiptReference(company, dealer);
  }

  String resolveSupplierPaymentReference(
      Company company, Supplier supplier, String providedReference, String idempotencyKey) {
    if (StringUtils.hasText(providedReference)) {
      return providedReference.trim();
    }
    if (company != null && StringUtils.hasText(idempotencyKey)) {
      String key = journalReplayService.normalizeIdempotencyMappingKey(idempotencyKey);
      JournalReferenceMapping mapping =
          journalReplayService.findLatestLegacyReferenceMapping(company, key).orElse(null);
      if (mapping != null && StringUtils.hasText(mapping.getCanonicalReference())) {
        return mapping.getCanonicalReference().trim();
      }
    }
    return referenceNumberService.supplierPaymentReference(company, supplier);
  }

  String resolveSupplierSettlementReference(
      Company company, Supplier supplier, PartnerSettlementRequest request, String idempotencyKey) {
    if (request != null && StringUtils.hasText(request.referenceNumber())) {
      return request.referenceNumber().trim();
    }
    if (company != null && StringUtils.hasText(idempotencyKey)) {
      String key = journalReplayService.normalizeIdempotencyMappingKey(idempotencyKey);
      JournalReferenceMapping mapping =
          journalReplayService.findLatestLegacyReferenceMapping(company, key).orElse(null);
      if (mapping != null && StringUtils.hasText(mapping.getCanonicalReference())) {
        return mapping.getCanonicalReference().trim();
      }
    }
    return referenceNumberService.supplierPaymentReference(company, supplier);
  }

  String buildDealerReceiptReference(Company company, Dealer dealer, DealerReceiptRequest request) {
    String dealerToken = sanitizeToken(dealer != null ? dealer.getCode() : null);
    if (request == null) {
      return referenceNumberService.nextJournalReference(company);
    }
    StringBuilder fingerprint = new StringBuilder();
    appendPartnerFingerprint(
        fingerprint, PartnerType.DEALER, dealer != null ? dealer.getId() : null);
    fingerprint
        .append("|cashAccountId=")
        .append(request.cashAccountId() != null ? request.cashAccountId() : "null")
        .append("|amount=")
        .append(normalizeDecimal(request.amount()));
    List<SettlementAllocationRequest> allocations =
        request.allocations() != null
            ? request.allocations().stream()
                .sorted(
                    Comparator.comparing(
                        SettlementAllocationRequest::invoiceId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList()
            : List.of();
    for (SettlementAllocationRequest allocation : allocations) {
      fingerprint
          .append("|inv=")
          .append(allocation.invoiceId() != null ? allocation.invoiceId() : "null")
          .append(":")
          .append(normalizeDecimal(allocation.appliedAmount()));
    }
    return "RCPT-%s-%s"
        .formatted(dealerToken, IdempotencyUtils.sha256Hex(fingerprint.toString(), 12));
  }

  String buildDealerReceiptReference(
      Company company, Dealer dealer, DealerReceiptSplitRequest request) {
    String dealerToken = sanitizeToken(dealer != null ? dealer.getCode() : null);
    if (request == null) {
      return referenceNumberService.nextJournalReference(company);
    }
    StringBuilder fingerprint = new StringBuilder();
    appendPartnerFingerprint(
        fingerprint, PartnerType.DEALER, dealer != null ? dealer.getId() : null);
    List<DealerReceiptSplitRequest.IncomingLine> lines =
        request.incomingLines() != null
            ? request.incomingLines().stream()
                .sorted(
                    Comparator.comparing(
                        DealerReceiptSplitRequest.IncomingLine::accountId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList()
            : List.of();
    for (DealerReceiptSplitRequest.IncomingLine line : lines) {
      fingerprint
          .append("|acc=")
          .append(line.accountId() != null ? line.accountId() : "null")
          .append(":")
          .append(normalizeDecimal(line.amount()));
    }
    return "RCPT-%s-%s"
        .formatted(dealerToken, IdempotencyUtils.sha256Hex(fingerprint.toString(), 12));
  }

  String buildSupplierAutoSettlementReference(
      Supplier supplier,
      Long cashAccountId,
      BigDecimal amount,
      List<SettlementAllocationRequest> allocations) {
    String supplierToken = sanitizeToken(supplier != null ? supplier.getCode() : null);
    StringBuilder fingerprint = new StringBuilder();
    appendPartnerFingerprint(
        fingerprint, PartnerType.SUPPLIER, supplier != null ? supplier.getId() : null);
    fingerprint
        .append("|cashAccountId=")
        .append(cashAccountId != null ? cashAccountId : "null")
        .append("|amount=")
        .append(normalizeDecimal(amount));
    List<SettlementAllocationRequest> ordered =
        allocations != null
            ? allocations.stream()
                .sorted(
                    Comparator.comparing(
                        SettlementAllocationRequest::purchaseId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList()
            : List.of();
    for (SettlementAllocationRequest allocation : ordered) {
      fingerprint
          .append("|pur=")
          .append(allocation.purchaseId() != null ? allocation.purchaseId() : "null")
          .append(":")
          .append(normalizeDecimal(allocation.appliedAmount()));
    }
    return "SUP-SET-%s-%s"
        .formatted(supplierToken, IdempotencyUtils.sha256Hex(fingerprint.toString(), 12));
  }

  private String buildDealerSettlementIdempotencyKey(PartnerSettlementRequest request) {
    if (request == null) {
      return UUID.randomUUID().toString();
    }
    StringBuilder fingerprint = new StringBuilder();
    appendPartnerFingerprint(fingerprint, PartnerType.DEALER, request.partnerId());
    fingerprint
        .append("|cashAccountId=")
        .append(request.cashAccountId() != null ? request.cashAccountId() : "null");
    if (request.amount() != null) {
      fingerprint.append("|amount=").append(normalizeDecimal(request.amount()));
    }
    List<SettlementAllocationRequest> allocations =
        request.allocations() != null
            ? request.allocations().stream()
                .sorted(
                    Comparator.comparing(
                            SettlementAllocationRequest::invoiceId,
                            Comparator.nullsLast(Long::compareTo))
                        .thenComparing(
                            SettlementAllocationRequest::purchaseId,
                            Comparator.nullsLast(Long::compareTo))
                        .thenComparing(
                            allocation -> resolveSettlementApplicationType(allocation).name()))
                .toList()
            : List.of();
    for (SettlementAllocationRequest allocation : allocations) {
      fingerprint
          .append("|inv=")
          .append(allocation.invoiceId() != null ? allocation.invoiceId() : "null")
          .append(":pur=")
          .append(allocation.purchaseId() != null ? allocation.purchaseId() : "null")
          .append(":")
          .append(normalizeDecimal(allocation.appliedAmount()))
          .append(":disc=")
          .append(normalizeDecimal(allocation.discountAmount()))
          .append(":woff=")
          .append(normalizeDecimal(allocation.writeOffAmount()))
          .append(":fx=")
          .append(normalizeDecimal(allocation.fxAdjustment()))
          .append(":app=")
          .append(resolveSettlementApplicationType(allocation).name());
    }
    return "DEALER-SETTLEMENT-" + IdempotencyUtils.sha256Hex(fingerprint.toString(), 12);
  }

  private String buildSupplierSettlementIdempotencyKey(PartnerSettlementRequest request) {
    if (request == null) {
      return UUID.randomUUID().toString();
    }
    StringBuilder fingerprint = new StringBuilder();
    appendPartnerFingerprint(fingerprint, PartnerType.SUPPLIER, request.partnerId());
    fingerprint
        .append("|cashAccountId=")
        .append(request.cashAccountId() != null ? request.cashAccountId() : "null");
    List<SettlementAllocationRequest> allocations =
        request.allocations() != null
            ? request.allocations().stream()
                .sorted(
                    Comparator.comparing(
                            SettlementAllocationRequest::purchaseId,
                            Comparator.nullsLast(Long::compareTo))
                        .thenComparing(
                            SettlementAllocationRequest::invoiceId,
                            Comparator.nullsLast(Long::compareTo))
                        .thenComparing(
                            allocation -> resolveSettlementApplicationType(allocation).name()))
                .toList()
            : List.of();
    for (SettlementAllocationRequest allocation : allocations) {
      fingerprint
          .append("|pur=")
          .append(allocation.purchaseId() != null ? allocation.purchaseId() : "null")
          .append(":inv=")
          .append(allocation.invoiceId() != null ? allocation.invoiceId() : "null")
          .append(":")
          .append(normalizeDecimal(allocation.appliedAmount()))
          .append(":disc=")
          .append(normalizeDecimal(allocation.discountAmount()))
          .append(":woff=")
          .append(normalizeDecimal(allocation.writeOffAmount()))
          .append(":fx=")
          .append(normalizeDecimal(allocation.fxAdjustment()))
          .append(":app=")
          .append(resolveSettlementApplicationType(allocation).name());
    }
    return "SUPPLIER-SETTLEMENT-" + IdempotencyUtils.sha256Hex(fingerprint.toString(), 12);
  }

  private SettlementAllocationApplication resolveSettlementApplicationType(
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

  private String sanitizeToken(String value) {
    String normalized = IdempotencyUtils.normalizeUpperToken(value).replaceAll("[^A-Z0-9]", "");
    if (normalized.isBlank()) {
      return "TOKEN";
    }
    return normalized.length() > 16 ? normalized.substring(0, 16) : normalized;
  }

  private String normalizeDecimal(BigDecimal value) {
    return IdempotencyUtils.normalizeDecimal(value);
  }

  private void appendPartnerFingerprint(
      StringBuilder fingerprint, PartnerType partnerType, Long partnerId) {
    fingerprint
        .append(partnerType == PartnerType.SUPPLIER ? "supplierId" : "dealerId")
        .append("=")
        .append(partnerId != null ? partnerId : "null");
  }
}
