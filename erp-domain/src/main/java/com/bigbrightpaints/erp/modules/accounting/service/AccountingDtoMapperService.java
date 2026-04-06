package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

@Service
class AccountingDtoMapperService {

  private final JournalReferenceMappingRepository journalReferenceMappingRepository;

  AccountingDtoMapperService(JournalReferenceMappingRepository journalReferenceMappingRepository) {
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
  }

  AccountDto toAccountDto(Account account) {
    return new AccountDto(
        account.getId(),
        account.getPublicId(),
        account.getCode(),
        account.getName(),
        account.getType(),
        account.getBalance());
  }

  JournalEntryDto toJournalEntryDto(JournalEntry entry) {
    return toJournalEntryDto(entry, entry.getReferenceNumber());
  }

  JournalEntryDto toJournalEntryDto(JournalEntry entry, String displayReferenceNumber) {
    List<JournalLineDto> lines =
        entry.getLines().stream()
            .map(
                line ->
                    new JournalLineDto(
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getDescription(),
                        line.getDebit(),
                        line.getCredit()))
            .toList();
    Dealer dealer = entry.getDealer();
    Supplier supplier = entry.getSupplier();
    AccountingPeriod period = entry.getAccountingPeriod();
    JournalEntry reversalOf = entry.getReversalOf();
    JournalEntry reversalEntry = entry.getReversalEntry();
    return new JournalEntryDto(
        entry.getId(),
        entry.getPublicId(),
        displayReferenceNumber,
        entry.getEntryDate(),
        entry.getMemo(),
        entry.getStatus() != null ? entry.getStatus().name() : null,
        dealer != null ? dealer.getId() : null,
        dealer != null ? dealer.getName() : null,
        supplier != null ? supplier.getId() : null,
        supplier != null ? supplier.getName() : null,
        period != null ? period.getId() : null,
        period != null ? period.getLabel() : null,
        period != null && period.getStatus() != null ? period.getStatus().name() : null,
        reversalOf != null ? reversalOf.getId() : null,
        reversalEntry != null ? reversalEntry.getId() : null,
        entry.getCorrectionType() != null ? entry.getCorrectionType().name() : null,
        entry.getCorrectionReason(),
        entry.getVoidReason(),
        lines,
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getPostedAt(),
        entry.getCreatedBy(),
        entry.getPostedBy(),
        entry.getLastModifiedBy());
  }

  JournalListItemDto toJournalListItemDto(JournalEntry entry) {
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    if (entry.getLines() != null) {
      for (JournalLine line : entry.getLines()) {
        totalDebit = totalDebit.add(line.getDebit() == null ? BigDecimal.ZERO : line.getDebit());
        totalCredit =
            totalCredit.add(line.getCredit() == null ? BigDecimal.ZERO : line.getCredit());
      }
    }
    return new JournalListItemDto(
        entry.getId(),
        entry.getReferenceNumber(),
        entry.getEntryDate(),
        entry.getMemo(),
        entry.getStatus() != null ? entry.getStatus().name() : null,
        entry.getJournalType() != null
            ? entry.getJournalType().name()
            : JournalEntryType.AUTOMATED.name(),
        entry.getSourceModule(),
        entry.getSourceReference(),
        totalDebit,
        totalCredit);
  }

  String resolveDisplayReferenceNumber(Company company, JournalEntry entry) {
    if (entry == null || !StringUtils.hasText(entry.getReferenceNumber())) {
      return entry != null ? entry.getReferenceNumber() : null;
    }
    String canonicalReference = entry.getReferenceNumber().trim();
    List<JournalReferenceMapping> mappings =
        journalReferenceMappingRepository.findAllByCompanyAndCanonicalReferenceIgnoreCase(
            company, canonicalReference);
    if (mappings.isEmpty()) {
      return canonicalReference;
    }
    return mappings.stream()
        .map(JournalReferenceMapping::getLegacyReference)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .filter(legacyReference -> !legacyReference.equalsIgnoreCase(canonicalReference))
        .sorted(
            Comparator.comparing(
                    (String reference) ->
                        reference.toUpperCase(Locale.ROOT).contains("-INV-") ? 0 : 1)
                .thenComparingInt(String::length))
        .findFirst()
        .orElse(canonicalReference);
  }
}
