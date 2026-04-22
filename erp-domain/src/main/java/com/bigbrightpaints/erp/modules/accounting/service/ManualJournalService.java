package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
class ManualJournalService {

  private final JournalPostingService journalPostingService;
  private final CompanyContextService companyContextService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalReferenceResolver journalReferenceResolver;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;
  private final JournalReplayService journalReplayService;
  private final JournalReferenceService journalReferenceService;
  private final AccountingDtoMapperService dtoMapperService;

  ManualJournalService(
      JournalPostingService journalPostingService,
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository,
      JournalReplayService journalReplayService,
      JournalReferenceService journalReferenceService,
      AccountingDtoMapperService dtoMapperService) {
    this.journalPostingService = journalPostingService;
    this.companyContextService = companyContextService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalReferenceResolver = journalReferenceResolver;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
    this.journalReplayService = journalReplayService;
    this.journalReferenceService = journalReferenceService;
    this.dtoMapperService = dtoMapperService;
  }

  JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Journal entry request is required");
    }
    var company = companyContextService.requireCurrentCompany();
    String rawKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
    String key =
        StringUtils.hasText(rawKey)
            ? journalReplayService.normalizeIdempotencyMappingKey(rawKey)
            : null;
    if (StringUtils.hasText(rawKey)) {
      Optional<JournalEntry> existingByReference =
          journalEntryRepository.findByCompanyAndReferenceNumber(company, rawKey);
      if (existingByReference.isPresent()) {
        return replayExistingEntryIfEquivalent(existingByReference.get(), request, company, rawKey);
      }
      Optional<JournalEntry> existingByResolver =
          journalReferenceResolver.findExistingEntry(company, rawKey);
      if (existingByResolver.isPresent()) {
        return replayExistingEntryIfEquivalent(existingByResolver.get(), request, company, rawKey);
      }
      int reserved =
          journalReferenceMappingRepository.reserveManualReference(
              company.getId(),
              key,
              journalReplayService.reservedManualReference(key),
              "JOURNAL_ENTRY",
              CompanyTime.now(company));
      if (reserved == 0) {
        JournalEntry already = journalReplayService.awaitJournalEntry(company, rawKey, key);
        if (already != null) {
          return replayExistingEntryIfEquivalent(already, request, company, rawKey);
        }
        throw new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                "Manual journal idempotency key already reserved but entry not found")
            .withDetail("referenceNumber", rawKey);
      }
    }
    JournalEntryDto created;
    try {
      created =
          journalPostingService.createJournalEntry(
              new JournalEntryRequest(
                  null,
                  request.entryDate(),
                  request.memo(),
                  request.dealerId(),
                  request.supplierId(),
                  request.adminOverride(),
                  request.lines(),
                  request.currency(),
                  request.fxRate(),
                  StringUtils.hasText(request.sourceModule()) ? request.sourceModule() : "MANUAL",
                  request.sourceReference(),
                  StringUtils.hasText(request.journalType())
                      ? request.journalType()
                      : JournalEntryType.MANUAL.name(),
                  request.attachmentReferences()));
    } catch (RuntimeException ex) {
      if (!StringUtils.hasText(rawKey)
          || !journalReplayService.isRetryableManualConcurrencyFailure(ex)) {
        throw ex;
      }
      JournalEntry already = journalReplayService.awaitJournalEntry(company, rawKey, key);
      if (already != null) {
        return replayExistingEntryIfEquivalent(already, request, company, rawKey);
      }
      throw ex;
    }
    if (StringUtils.hasText(key)
        && created != null
        && StringUtils.hasText(created.referenceNumber())) {
      JournalReferenceMapping mapping =
          journalReplayService
              .findLatestLegacyReferenceMapping(company, key)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                              ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                              "Manual journal idempotency reservation missing")
                          .withDetail("referenceNumber", rawKey));
      mapping.setCanonicalReference(created.referenceNumber());
      mapping.setEntityId(created.id());
      journalReferenceMappingRepository.save(mapping);
    }
    return created;
  }

  private JournalEntryDto replayExistingEntryIfEquivalent(
      JournalEntry existingEntry,
      JournalEntryRequest request,
      Company company,
      String idempotencyKey) {
    ensureReplayPayloadMatchesExisting(existingEntry, request, company, idempotencyKey);
    return dtoMapperService.toJournalEntryDto(existingEntry);
  }

  private void ensureReplayPayloadMatchesExisting(
      JournalEntry existingEntry,
      JournalEntryRequest request,
      Company company,
      String idempotencyKey) {
    if (existingEntry == null || request == null) {
      return;
    }
    List<String> mismatches = new ArrayList<>();
    if (!Objects.equals(existingEntry.getEntryDate(), request.entryDate())) {
      mismatches.add("entryDate");
    }
    if (!Objects.equals(existingDealerId(existingEntry), request.dealerId())) {
      mismatches.add("dealerId");
    }
    if (!Objects.equals(existingSupplierId(existingEntry), request.supplierId())) {
      mismatches.add("supplierId");
    }
    if (!Objects.equals(existingEntry.getMemo(), normalizeMemo(request.memo()))) {
      mismatches.add("memo");
    }

    String requestedCurrency = journalReferenceService.resolveCurrency(request.currency(), company);
    BigDecimal requestedFxRate =
        journalReferenceService.resolveFxRate(requestedCurrency, company, request.fxRate());
    if (!sameCurrency(existingEntry.getCurrency(), requestedCurrency)) {
      mismatches.add("currency");
    }
    if (!sameFxRate(existingEntry.getFxRate(), requestedFxRate)) {
      mismatches.add("fxRate");
    }
    if (!lineSignatureCounts(existingEntry.getLines())
        .equals(lineSignatureCountsFromRequest(request.lines(), requestedFxRate))) {
      mismatches.add("lines");
    }
    if (!mismatches.isEmpty()) {
      throw replayConflict(idempotencyKey, existingEntry, mismatches);
    }
  }

  private Long existingDealerId(JournalEntry entry) {
    return entry != null && entry.getDealer() != null ? entry.getDealer().getId() : null;
  }

  private Long existingSupplierId(JournalEntry entry) {
    return entry != null && entry.getSupplier() != null ? entry.getSupplier().getId() : null;
  }

  private String normalizeMemo(String memo) {
    return StringUtils.hasText(memo) ? memo.trim() : null;
  }

  private boolean sameCurrency(String left, String right) {
    if (left == null && right == null) {
      return true;
    }
    return left != null && right != null && left.equalsIgnoreCase(right);
  }

  private boolean sameFxRate(BigDecimal left, BigDecimal right) {
    BigDecimal normalizedLeft = left == null ? BigDecimal.ONE : left;
    BigDecimal normalizedRight = right == null ? BigDecimal.ONE : right;
    return normalizedLeft.compareTo(normalizedRight) == 0;
  }

  private Map<LineSignature, Integer> lineSignatureCounts(List<JournalLine> lines) {
    Map<LineSignature, Integer> counts = new HashMap<>();
    if (lines == null) {
      return counts;
    }
    for (JournalLine line : lines) {
      if (line == null || line.getAccount() == null || line.getAccount().getId() == null) {
        continue;
      }
      LineSignature signature =
          new LineSignature(
              line.getAccount().getId(),
              normalizeAmount(line.getDebit()),
              normalizeAmount(line.getCredit()));
      counts.merge(signature, 1, Integer::sum);
    }
    return counts;
  }

  private Map<LineSignature, Integer> lineSignatureCountsFromRequest(
      List<JournalEntryRequest.JournalLineRequest> lines, BigDecimal fxRate) {
    Map<LineSignature, Integer> counts = new HashMap<>();
    if (lines == null) {
      return counts;
    }
    for (JournalEntryRequest.JournalLineRequest line : lines) {
      if (line == null || line.accountId() == null) {
        continue;
      }
      BigDecimal baseDebit =
          journalReferenceService.toBaseCurrency(
              line.debit() == null ? BigDecimal.ZERO : line.debit(), fxRate);
      BigDecimal baseCredit =
          journalReferenceService.toBaseCurrency(
              line.credit() == null ? BigDecimal.ZERO : line.credit(), fxRate);
      LineSignature signature =
          new LineSignature(
              line.accountId(), normalizeAmount(baseDebit), normalizeAmount(baseCredit));
      counts.merge(signature, 1, Integer::sum);
    }
    return counts;
  }

  private BigDecimal normalizeAmount(BigDecimal amount) {
    if (amount == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return amount.setScale(2, RoundingMode.HALF_UP);
  }

  private ApplicationException replayConflict(
      String idempotencyKey, JournalEntry existingEntry, List<String> mismatches) {
    String normalizedIdempotencyKey =
        StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : idempotencyKey;
    ApplicationException exception =
        new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Manual journal replay conflict: idempotency key already used with materially"
                    + " different payload")
            .withDetail("outcome", "replay-conflict")
            .withDetail("referenceNumber", normalizedIdempotencyKey)
            .withDetail("mismatches", mismatches);
    if (existingEntry != null) {
      exception
          .withDetail("existingReferenceNumber", existingEntry.getReferenceNumber())
          .withDetail("existingJournalEntryId", existingEntry.getId());
    }
    return exception;
  }

  private record LineSignature(Long accountId, BigDecimal debit, BigDecimal credit) {}
}
