package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
class JournalPostingService {

  private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = BigDecimal.ZERO;

  private final CompanyContextService companyContextService;
  private final CompanyClock companyClock;
  private final JournalEntryMutationService journalEntryMutationService;
  private final PeriodValidationService periodValidationService;
  private final JournalReferenceService journalReferenceService;
  private final AccountingDtoMapperService dtoMapperService;

  JournalPostingService(
      CompanyContextService companyContextService,
      CompanyClock companyClock,
      JournalEntryMutationService journalEntryMutationService,
      PeriodValidationService periodValidationService,
      JournalReferenceService journalReferenceService,
      AccountingDtoMapperService dtoMapperService) {
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
    this.journalEntryMutationService = journalEntryMutationService;
    this.periodValidationService = periodValidationService;
    this.journalReferenceService = journalReferenceService;
    this.dtoMapperService = dtoMapperService;
  }

  @Transactional
  JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Journal creation request is required");
    }
    ValidationUtils.requirePositive(request.amount(), "amount");
    if (!StringUtils.hasText(request.narration())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Narration is required for journal creation");
    }
    if (!StringUtils.hasText(request.sourceModule())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Source module is required for journal creation");
    }
    if (!StringUtils.hasText(request.sourceReference())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Source reference is required for journal creation");
    }
    List<JournalEntryRequest.JournalLineRequest> resolvedLines = request.resolvedLines();
    validateBalancedLines(resolvedLines, "At least one journal line is required");
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : companyClock.today(companyContextService.requireCurrentCompany());
    String sourceModule = request.sourceModule().trim();
    String sourceReference = request.sourceReference().trim();
    return createJournalEntry(
        new JournalEntryRequest(
            sourceReference,
            entryDate,
            request.narration().trim(),
            request.dealerId(),
            request.supplierId(),
            Boolean.TRUE.equals(request.adminOverride()),
            resolvedLines,
            null,
            null,
            sourceModule,
            sourceReference,
            "MANUAL".equalsIgnoreCase(sourceModule)
                ? JournalEntryType.MANUAL.name()
                : JournalEntryType.AUTOMATED.name(),
            request.attachmentReferences()));
  }

  @Transactional
  JournalEntryDto createManualJournal(ManualJournalRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Manual journal request is required");
    }
    if (!StringUtils.hasText(request.narration())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Manual journal reason is required");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Manual journal requires at least one line");
    }
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    for (ManualJournalRequest.LineRequest line : request.lines()) {
      if (line == null || line.accountId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
            "Account is required for manual journal lines");
      }
      BigDecimal amount = ValidationUtils.requirePositive(line.amount(), "amount");
      if (line.entryType() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
            "Entry type is required for manual journal lines");
      }
      boolean debitLine = line.entryType() == ManualJournalRequest.EntryType.DEBIT;
      BigDecimal debit = debitLine ? amount : BigDecimal.ZERO;
      BigDecimal credit = debitLine ? BigDecimal.ZERO : amount;
      totalDebit = totalDebit.add(debit);
      totalCredit = totalCredit.add(credit);
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              line.accountId(),
              StringUtils.hasText(line.narration())
                  ? line.narration().trim()
                  : request.narration().trim(),
              debit,
              credit));
    }
    if (totalDebit.subtract(totalCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Manual journal entry must balance")
          .withDetail("totalDebit", totalDebit)
          .withDetail("totalCredit", totalCredit);
    }
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : companyClock.today(companyContextService.requireCurrentCompany());
    return createJournalEntry(
        new JournalEntryRequest(
            null,
            entryDate,
            request.narration().trim(),
            null,
            null,
            Boolean.TRUE.equals(request.adminOverride()),
            lines,
            null,
            null,
            "MANUAL",
            StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : null,
            JournalEntryType.MANUAL.name(),
            request.attachmentReferences()));
  }

  @Transactional
  JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryMutationService.createJournalEntry(request);
  }

  @Transactional
  JournalEntryMutationOutcome createJournalEntryWithOutcome(JournalEntryRequest request) {
    return journalEntryMutationService.createJournalEntryWithOutcome(request);
  }

  JournalEntryDto createJournalEntryForReversal(
      JournalEntryRequest payload, boolean allowClosedPeriodOverride) {
    return allowClosedPeriodOverride
        ? runWithSystemEntryDateOverride(() -> createJournalEntry(payload))
        : createJournalEntry(payload);
  }

  <T> T runWithSystemEntryDateOverride(java.util.function.Supplier<T> action) {
    return periodValidationService.runWithSystemEntryDateOverride(action);
  }

  boolean hasEntryDateOverrideAuthority() {
    return periodValidationService.hasEntryDateOverrideAuthority();
  }

  LocalDate currentDate(Company company) {
    return periodValidationService.currentDate(company);
  }

  void validateEntryDate(
      Company company, LocalDate entryDate, boolean overrideRequested, boolean overrideAuthorized) {
    periodValidationService.validateEntryDate(
        company, entryDate, overrideRequested, overrideAuthorized);
  }

  String resolvePostingDocumentType(JournalEntry entry) {
    return journalReferenceService.resolvePostingDocumentType(entry);
  }

  String resolvePostingDocumentReference(JournalEntry entry) {
    return journalReferenceService.resolvePostingDocumentReference(entry);
  }

  JournalEntryDto toDto(JournalEntry entry) {
    return dtoMapperService.toJournalEntryDto(entry);
  }

  private void validateBalancedLines(
      List<JournalEntryRequest.JournalLineRequest> lines, String emptyMessage) {
    if (lines == null || lines.isEmpty()) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, emptyMessage);
    }
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    for (JournalEntryRequest.JournalLineRequest line : lines) {
      if (line.accountId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
            "Account is required for every journal line");
      }
      BigDecimal debit = line.debit() == null ? BigDecimal.ZERO : line.debit();
      BigDecimal credit = line.credit() == null ? BigDecimal.ZERO : line.credit();
      if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Journal line amounts cannot be negative");
      }
      if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Debit and credit cannot both be non-zero on the same line");
      }
      totalDebit = totalDebit.add(debit);
      totalCredit = totalCredit.add(credit);
    }
    if (totalDebit.compareTo(BigDecimal.ZERO) <= 0 || totalCredit.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Journal lines must include at least one debit and one credit");
    }
    if (totalDebit.subtract(totalCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance")
          .withDetail("totalDebit", totalDebit)
          .withDetail("totalCredit", totalCredit);
    }
  }
}
