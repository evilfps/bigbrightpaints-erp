package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
class JournalQueryService {

  private final CompanyContextService companyContextService;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingDtoMapperService accountingDtoMapperService;
  private final AccountResolutionService accountResolutionService;

  JournalQueryService(
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      AccountingDtoMapperService accountingDtoMapperService,
      AccountResolutionService accountResolutionService) {
    this.companyContextService = companyContextService;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingDtoMapperService = accountingDtoMapperService;
    this.accountResolutionService = accountResolutionService;
  }

  List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
    if (dealerId != null && supplierId != null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Only one of dealerId or supplierId can be provided");
    }
    Company company = companyContextService.requireCurrentCompany();
    int safeSize = Math.max(1, Math.min(size, 200));
    PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
    List<JournalEntry> entries;
    if (dealerId != null) {
      entries =
          journalEntryRepository
              .findByCompanyAndDealerOrderByEntryDateDescIdDesc(
                  company, accountResolutionService.requireDealer(company, dealerId), pageable)
              .getContent();
    } else if (supplierId != null) {
      entries =
          journalEntryRepository
              .findByCompanyAndSupplierOrderByEntryDateDescIdDesc(
                  company, accountResolutionService.requireSupplier(company, supplierId), pageable)
              .getContent();
    } else {
      entries =
          journalEntryRepository.findByCompanyOrderByEntryDateDescIdDesc(company, pageable).getContent();
    }
    return entries.stream()
        .map(
            entry ->
                accountingDtoMapperService.toJournalEntryDto(
                    entry, accountingDtoMapperService.resolveDisplayReferenceNumber(company, entry)))
        .toList();
  }

  List<JournalEntryDto> listJournalEntries(Long dealerId) {
    return listJournalEntries(dealerId, null, 0, 100);
  }

  List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
    Company company = companyContextService.requireCurrentCompany();
    return journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, prefix).stream()
        .map(accountingDtoMapperService::toJournalEntryDto)
        .toList();
  }

  PageResponse<JournalListItemDto> listJournals(
      LocalDate fromDate,
      LocalDate toDate,
      String journalType,
      String sourceModule,
      int page,
      int size) {
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_DATE, "fromDate cannot be after toDate")
          .withDetail("fromDate", fromDate)
          .withDetail("toDate", toDate);
    }
    JournalEntryType typeFilter = parseJournalTypeFilter(journalType);
    String normalizedSourceModule = normalizeSourceModule(sourceModule);
    Company company = companyContextService.requireCurrentCompany();
    int safePage = Math.max(page, 0);
    int safeSize = Math.max(1, Math.min(size, 200));
    Specification<JournalEntry> spec =
        Specification.where(byJournalCompany(company))
            .and(byJournalEntryDateRange(fromDate, toDate))
            .and(byJournalType(typeFilter))
            .and(byJournalSourceModule(normalizedSourceModule));
    Page<JournalEntry> journalPage =
        journalEntryRepository.findAll(
            spec,
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "entryDate", "id")));
    return PageResponse.of(
        journalPage.getContent().stream().map(accountingDtoMapperService::toJournalListItemDto).toList(),
        journalPage.getTotalElements(),
        safePage,
        safeSize);
  }

  JournalEntryType parseJournalTypeFilter(String journalType) {
    if (journalType == null || journalType.isBlank()) {
      return null;
    }
    try {
      return JournalEntryType.valueOf(journalType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Invalid journalType filter")
          .withDetail("journalType", journalType);
    }
  }

  String normalizeSourceModule(String sourceModule) {
    if (sourceModule == null || sourceModule.isBlank()) {
      return null;
    }
    return sourceModule.trim().toUpperCase(Locale.ROOT);
  }

  private Specification<JournalEntry> byJournalCompany(Company company) {
    return (root, query, cb) -> cb.equal(root.get("company"), company);
  }

  private Specification<JournalEntry> byJournalEntryDateRange(
      LocalDate fromDate, LocalDate toDate) {
    return (root, query, cb) -> {
      if (fromDate == null && toDate == null) {
        return cb.conjunction();
      }
      if (fromDate != null && toDate != null) {
        return cb.between(root.get("entryDate"), fromDate, toDate);
      }
      if (fromDate != null) {
        return cb.greaterThanOrEqualTo(root.get("entryDate"), fromDate);
      }
      return cb.lessThanOrEqualTo(root.get("entryDate"), toDate);
    };
  }

  private Specification<JournalEntry> byJournalType(JournalEntryType typeFilter) {
    return (root, query, cb) ->
        typeFilter != null ? cb.equal(root.get("journalType"), typeFilter) : cb.conjunction();
  }

  private Specification<JournalEntry> byJournalSourceModule(String normalizedSourceModule) {
    return (root, query, cb) ->
        normalizedSourceModule != null
            ? cb.equal(
                cb.lower(root.get("sourceModule")), normalizedSourceModule.toLowerCase(Locale.ROOT))
            : cb.conjunction();
  }
}
