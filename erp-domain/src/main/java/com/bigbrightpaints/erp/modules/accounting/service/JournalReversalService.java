package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
class JournalReversalService {

  private final CompanyContextService companyContextService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingPeriodService accountingPeriodService;
  private final com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService;
  private final ReferenceNumberService referenceNumberService;
  private final JournalPostingService journalPostingService;
  private final AccountingAuditService accountingAuditService;
  private final AccountingComplianceAuditService accountingComplianceAuditService;

  JournalReversalService(
      CompanyContextService companyContextService,
      CompanyScopedAccountingLookupService accountingLookupService,
      JournalEntryRepository journalEntryRepository,
      AccountingPeriodService accountingPeriodService,
      com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService,
      ReferenceNumberService referenceNumberService,
      JournalPostingService journalPostingService,
      AccountingAuditService accountingAuditService,
      org.springframework.beans.factory.ObjectProvider<AccountingComplianceAuditService>
          accountingComplianceAuditServiceProvider) {
    this.companyContextService = companyContextService;
    this.accountingLookupService = accountingLookupService;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingPeriodService = accountingPeriodService;
    this.systemSettingsService = systemSettingsService;
    this.referenceNumberService = referenceNumberService;
    this.journalPostingService = journalPostingService;
    this.accountingAuditService = accountingAuditService;
    this.accountingComplianceAuditService =
        accountingComplianceAuditServiceProvider.getIfAvailable();
  }

  @Transactional
  JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryId);
    return reverseJournalEntryInternal(company, entry, request);
  }

  @Transactional
  JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    if (entry == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Closing journal entry is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    LocalDate reversalDate =
        entry.getEntryDate() != null
            ? entry.getEntryDate()
            : (period != null ? period.getEndDate() : journalPostingService.currentDate(company));
    LocalDate today = journalPostingService.currentDate(company);
    if (reversalDate != null && reversalDate.isAfter(today)) {
      reversalDate = today;
    }
    String memo = "Reopen reversal of " + entry.getReferenceNumber();
    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            reversalDate,
            false,
            StringUtils.hasText(reason) ? reason.trim() : "Period reopen",
            memo,
            Boolean.TRUE);
    return journalPostingService.runWithSystemEntryDateOverride(
        () -> reverseJournalEntryInternal(company, entry, request));
  }

  private JournalEntryDto reverseJournalEntryInternal(
      Company company, JournalEntry entry, JournalEntryReversalRequest request) {
    rejectCascadeReversalRequest(request);
    rejectPartialVoidReversalRequest(request);
    if (entry.getStatus() == JournalEntryStatus.VOIDED) {
      throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Entry is already voided");
    }
    if (entry.getStatus() == JournalEntryStatus.REVERSED) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Entry has already been reversed");
    }

    LocalDate reversalDate =
        request != null && request.reversalDate() != null
            ? request.reversalDate()
            : journalPostingService.currentDate(company);
    boolean overrideRequested = request != null && Boolean.TRUE.equals(request.adminOverride());
    boolean overrideAuthorityAvailable = journalPostingService.hasEntryDateOverrideAuthority();
    boolean overrideAuthorized = overrideRequested && overrideAuthorityAvailable;
    AccountingPeriod originalPeriod = entry.getAccountingPeriod();
    if (originalPeriod != null) {
      if (originalPeriod.getStatus() == AccountingPeriodStatus.LOCKED) {
        throw new ApplicationException(
            ErrorCode.BUSINESS_INVALID_STATE,
            "Cannot reverse entry from LOCKED period "
                + originalPeriod.getYear()
                + "-"
                + originalPeriod.getMonth()
                + ". Period must be unlocked first.");
      }
      if (originalPeriod.getStatus() == AccountingPeriodStatus.CLOSED && !overrideAuthorized) {
        throw new ApplicationException(
            ErrorCode.BUSINESS_INVALID_STATE,
            "Entry belongs to CLOSED period. Administrator override with audit approval required.");
      }
    }
    AccountingPeriod postingPeriod;
    if (systemSettingsService.isPeriodLockEnforced()) {
      journalPostingService.validateEntryDate(
          company, reversalDate, overrideRequested, overrideAuthorized);
      postingPeriod =
          accountingPeriodService.requirePostablePeriod(
              company,
              reversalDate,
              journalPostingService.resolvePostingDocumentType(entry),
              journalPostingService.resolvePostingDocumentReference(entry),
              request != null ? request.reason() : null,
              overrideAuthorized);
    } else {
      journalPostingService.validateEntryDate(
          company, reversalDate, overrideRequested, overrideAuthorized);
      postingPeriod = accountingPeriodService.ensurePeriod(company, reversalDate);
    }

    String sanitizedReason = buildAuditReason(request, entry);
    String memo =
        request != null && StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Reversal of " + entry.getReferenceNumber();
    BigDecimal reversalFactor =
        request != null && request.isPartialReversal()
            ? request
                .getEffectivePercentage()
                .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)
            : BigDecimal.ONE;
    boolean isPartial = request != null && request.isPartialReversal();
    String partialNote = isPartial ? " (" + request.getEffectivePercentage() + "% partial)" : "";

    List<JournalEntryRequest.JournalLineRequest> reversedLines =
        entry.getLines().stream()
            .map(
                line ->
                    new JournalEntryRequest.JournalLineRequest(
                        line.getAccount().getId(),
                        "Reversal"
                            + partialNote
                            + ": "
                            + (line.getDescription() == null
                                ? entry.getMemo()
                                : line.getDescription()),
                        line.getCredit().multiply(reversalFactor).setScale(2, RoundingMode.HALF_UP),
                        line.getDebit().multiply(reversalFactor).setScale(2, RoundingMode.HALF_UP)))
            .toList();
    JournalEntryRequest payload =
        new JournalEntryRequest(
            referenceNumberService.reversalReference(entry.getReferenceNumber()),
            reversalDate,
            memo,
            entry.getDealer() != null ? entry.getDealer().getId() : null,
            entry.getSupplier() != null ? entry.getSupplier().getId() : null,
            request != null ? request.adminOverride() : null,
            reversedLines);
    if (request != null && request.voidOnly()) {
      Instant now = CompanyTime.now(company);
      JournalEntryDto reversalDto =
          journalPostingService.createJournalEntryForReversal(payload, overrideAuthorized);
      JournalEntry reversalEntry =
          accountingLookupService.requireJournalEntry(company, reversalDto.id());
      reversalEntry.setReversalOf(entry);
      reversalEntry.setAccountingPeriod(postingPeriod);
      reversalEntry.setCorrectionType(JournalCorrectionType.VOID);
      reversalEntry.setCorrectionReason(sanitizedReason);
      reversalEntry.setLastModifiedBy(accountingAuditService.resolveCurrentUsername());
      journalEntryRepository.save(reversalEntry);

      entry.setStatus(JournalEntryStatus.VOIDED);
      entry.setCorrectionType(JournalCorrectionType.VOID);
      entry.setCorrectionReason(sanitizedReason);
      entry.setVoidReason(sanitizedReason);
      entry.setVoidedAt(now);
      entry.setReversalEntry(reversalEntry);
      entry.setLastModifiedBy(accountingAuditService.resolveCurrentUsername());
      journalEntryRepository.save(entry);
      var auditMetadata = new HashMap<String, String>();
      auditMetadata.put("originalJournalEntryId", entry.getId().toString());
      auditMetadata.put("reversalEntryId", reversalEntry.getId().toString());
      auditMetadata.put("reversalType", JournalCorrectionType.VOID.name());
      auditMetadata.put("reversalDate", reversalDate.toString());
      auditMetadata.put("partial", Boolean.toString(isPartial));
      auditMetadata.put("reversalFactor", reversalFactor.toPlainString());
      auditMetadata.put("adminOverrideRequested", Boolean.toString(overrideRequested));
      auditMetadata.put("adminOverrideAuthorized", Boolean.toString(overrideAuthorized));
      if (entry.getReferenceNumber() != null) {
        auditMetadata.put("referenceNumber", entry.getReferenceNumber());
      }
      if (sanitizedReason != null) {
        auditMetadata.put("reason", sanitizedReason);
      }
      accountingAuditService.recordJournalEntryVoidedEventSafe(
          entry, reversalEntry, sanitizedReason);
      accountingAuditService.logAuditSuccessAfterCommit(
          AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
      if (accountingComplianceAuditService != null) {
        accountingComplianceAuditService.recordJournalReversal(
            company, entry, reversalEntry, sanitizedReason);
        accountingComplianceAuditService.recordAdminOverrideJournalReversal(
            company,
            entry,
            reversalEntry,
            accountingAuditService.resolveCurrentUsername(),
            sanitizedReason,
            overrideRequested,
            overrideAuthorized);
      }
      return journalPostingService.toDto(reversalEntry);
    }
    JournalEntryDto reversalDto =
        journalPostingService.createJournalEntryForReversal(payload, overrideAuthorized);
    JournalEntry reversalEntry =
        accountingLookupService.requireJournalEntry(company, reversalDto.id());
    reversalEntry.setReversalOf(entry);
    reversalEntry.setAccountingPeriod(postingPeriod);
    reversalEntry.setCorrectionType(JournalCorrectionType.REVERSAL);
    reversalEntry.setCorrectionReason(sanitizedReason);
    reversalEntry.setLastModifiedBy(accountingAuditService.resolveCurrentUsername());
    journalEntryRepository.save(reversalEntry);
    entry.setStatus(JournalEntryStatus.REVERSED);
    entry.setCorrectionType(JournalCorrectionType.REVERSAL);
    entry.setCorrectionReason(sanitizedReason);
    entry.setVoidReason(null);
    entry.setVoidedAt(null);
    entry.setReversalEntry(reversalEntry);
    entry.setLastModifiedBy(accountingAuditService.resolveCurrentUsername());
    journalEntryRepository.save(entry);
    var auditMetadata = new HashMap<String, String>();
    auditMetadata.put("originalJournalEntryId", entry.getId().toString());
    auditMetadata.put("reversalEntryId", reversalEntry.getId().toString());
    auditMetadata.put("reversalType", JournalCorrectionType.REVERSAL.name());
    auditMetadata.put("reversalDate", reversalDate.toString());
    auditMetadata.put("partial", Boolean.toString(isPartial));
    auditMetadata.put("reversalFactor", reversalFactor.toPlainString());
    auditMetadata.put("adminOverrideRequested", Boolean.toString(overrideRequested));
    auditMetadata.put("adminOverrideAuthorized", Boolean.toString(overrideAuthorized));
    if (entry.getReferenceNumber() != null) {
      auditMetadata.put("referenceNumber", entry.getReferenceNumber());
    }
    if (sanitizedReason != null) {
      auditMetadata.put("reason", sanitizedReason);
    }
    accountingAuditService.recordJournalEntryReversedEventSafe(
        entry, reversalEntry, sanitizedReason);
    accountingAuditService.logAuditSuccessAfterCommit(
        AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordJournalReversal(
          company, entry, reversalEntry, sanitizedReason);
      accountingComplianceAuditService.recordAdminOverrideJournalReversal(
          company,
          entry,
          reversalEntry,
          accountingAuditService.resolveCurrentUsername(),
          sanitizedReason,
          overrideRequested,
          overrideAuthorized);
    }
    return journalPostingService.toDto(reversalEntry);
  }

  private void rejectCascadeReversalRequest(JournalEntryReversalRequest request) {
    if (request == null) {
      return;
    }
    if (!request.cascadeRelatedEntries()
        && (request.relatedEntryIds() == null || request.relatedEntryIds().isEmpty())) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Cascade reversal is not supported; reverse each related entry individually")
        .withDetail("cascadeRelatedEntries", request.cascadeRelatedEntries())
        .withDetail(
            "relatedEntryCount",
            request.relatedEntryIds() == null ? 0 : request.relatedEntryIds().size());
  }

  private void rejectPartialVoidReversalRequest(JournalEntryReversalRequest request) {
    if (request == null || !request.voidOnly() || !request.isPartialReversal()) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "voidOnly reversal cannot use reversalPercentage less than 100")
        .withDetail("voidOnly", true)
        .withDetail("reversalPercentage", request.getEffectivePercentage());
  }

  private String buildAuditReason(JournalEntryReversalRequest request, JournalEntry originalEntry) {
    if (request == null) {
      return "Adjustment";
    }
    StringBuilder reason = new StringBuilder();
    if (request.reasonCode() != null) {
      reason.append("[").append(request.reasonCode().name()).append("] ");
    }
    if (StringUtils.hasText(request.reason())) {
      reason.append(request.reason().trim());
    } else {
      reason.append("Reversal of ").append(originalEntry.getReferenceNumber());
    }
    if (request.isPartialReversal()) {
      reason.append(" (").append(request.getEffectivePercentage()).append("% partial reversal)");
    }
    if (StringUtils.hasText(request.approvedBy())) {
      reason.append(" | Approved by: ").append(request.approvedBy());
    }
    if (StringUtils.hasText(request.supportingDocumentRef())) {
      reason.append(" | Doc: ").append(request.supportingDocumentRef());
    }
    return reason.toString();
  }
}
