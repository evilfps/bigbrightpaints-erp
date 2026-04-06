package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;

final class AccountingFacadeJournalSupport {

  private AccountingFacadeJournalSupport() {}

  static List<JournalCreationRequest.LineRequest> toStandardLines(
      List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null) {
      return List.of();
    }
    return lines.stream()
        .map(
            line ->
                new JournalCreationRequest.LineRequest(
                    line.accountId(), line.debit(), line.credit(), line.description()))
        .toList();
  }

  static BigDecimal totalLineAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal debitTotal =
        lines.stream()
            .map(line -> line.debit() == null ? BigDecimal.ZERO : line.debit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal creditTotal =
        lines.stream()
            .map(line -> line.credit() == null ? BigDecimal.ZERO : line.credit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return (debitTotal.compareTo(BigDecimal.ZERO) > 0 ? debitTotal : creditTotal).abs();
  }

  static Long resolvePrimaryDebitAccount(
      List<JournalEntryRequest.JournalLineRequest> lines, Long fallback) {
    if (lines == null) {
      return fallback;
    }
    return lines.stream()
        .filter(line -> line.debit() != null && line.debit().compareTo(BigDecimal.ZERO) > 0)
        .map(JournalEntryRequest.JournalLineRequest::accountId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(fallback);
  }

  static Long resolvePrimaryCreditAccount(
      List<JournalEntryRequest.JournalLineRequest> lines, Long fallback) {
    if (lines == null) {
      return fallback;
    }
    return lines.stream()
        .filter(line -> line.credit() != null && line.credit().compareTo(BigDecimal.ZERO) > 0)
        .map(JournalEntryRequest.JournalLineRequest::accountId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(fallback);
  }

  static BigDecimal calculateTotalCredits(List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(JournalEntryRequest.JournalLineRequest::credit)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static BigDecimal calculateTotalDebits(List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(JournalEntryRequest.JournalLineRequest::debit)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static BigDecimal absoluteAmount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  static String sanitize(String value) {
    if (!StringUtils.hasText(value)) {
      return "GEN";
    }
    return value.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
  }

  static JournalEntryDto toSimpleDto(JournalEntry entry) {
    return new JournalEntryDto(
        entry.getId(),
        entry.getPublicId(),
        entry.getReferenceNumber(),
        entry.getEntryDate(),
        entry.getMemo(),
        entry.getStatus() != null ? entry.getStatus().name() : null,
        entry.getDealer() != null ? entry.getDealer().getId() : null,
        entry.getDealer() != null ? entry.getDealer().getName() : null,
        entry.getSupplier() != null ? entry.getSupplier().getId() : null,
        entry.getSupplier() != null ? entry.getSupplier().getName() : null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getPostedAt(),
        entry.getCreatedBy(),
        entry.getPostedBy(),
        entry.getLastModifiedBy());
  }
}
