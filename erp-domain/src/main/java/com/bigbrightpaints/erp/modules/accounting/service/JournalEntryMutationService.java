package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

import jakarta.persistence.EntityManager;

@Service
class JournalEntryMutationService {

  private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = BigDecimal.ZERO;

  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingPeriodService accountingPeriodService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final EntityManager entityManager;
  private final SystemSettingsService systemSettingsService;
  private final AuditService auditService;
  private final AccountingAuditService accountingAuditService;
  private final PeriodValidationService periodValidationService;
  private final AccountResolutionService accountResolutionService;
  private final JournalReferenceService journalReferenceService;
  private final AccountingDtoMapperService dtoMapperService;
  private final JournalPartnerContextService journalPartnerContextService;
  private final JournalDuplicateGuardService journalDuplicateGuardService;
  private final JournalLinePostingService journalLinePostingService;

  @Autowired(required = false)
  private AccountingComplianceAuditService accountingComplianceAuditService;

  @Autowired(required = false)
  private ClosedPeriodPostingExceptionService closedPeriodPostingExceptionService;

  JournalEntryMutationService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      JournalEntryRepository journalEntryRepository,
      AccountingPeriodService accountingPeriodService,
      CompanyScopedAccountingLookupService accountingLookupService,
      EntityManager entityManager,
      SystemSettingsService systemSettingsService,
      AuditService auditService,
      AccountingAuditService accountingAuditService,
      PeriodValidationService periodValidationService,
      AccountResolutionService accountResolutionService,
      JournalReferenceService journalReferenceService,
      AccountingDtoMapperService dtoMapperService,
      JournalPartnerContextService journalPartnerContextService,
      JournalDuplicateGuardService journalDuplicateGuardService,
      JournalLinePostingService journalLinePostingService) {
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingPeriodService = accountingPeriodService;
    this.accountingLookupService = accountingLookupService;
    this.entityManager = entityManager;
    this.systemSettingsService = systemSettingsService;
    this.auditService = auditService;
    this.accountingAuditService = accountingAuditService;
    this.periodValidationService = periodValidationService;
    this.accountResolutionService = accountResolutionService;
    this.journalReferenceService = journalReferenceService;
    this.dtoMapperService = dtoMapperService;
    this.journalPartnerContextService = journalPartnerContextService;
    this.journalDuplicateGuardService = journalDuplicateGuardService;
    this.journalLinePostingService = journalLinePostingService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return createJournalEntryWithOutcome(request).journalEntry();
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  JournalEntryMutationOutcome createJournalEntryWithOutcome(JournalEntryRequest request) {
    Map<String, String> auditMetadata = new HashMap<>();
    if (request != null && request.referenceNumber() != null) {
      auditMetadata.put("requestedReference", request.referenceNumber());
    }
    try {
      if (request == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Journal entry request is required");
      }
      Company company = companyContextService.requireCurrentCompany();
      List<JournalEntryRequest.JournalLineRequest> lines = request.lines();
      if (company.getId() != null) {
        auditMetadata.put("companyId", company.getId().toString());
      }
      if (lines == null || lines.isEmpty()) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "At least one journal line is required");
      }

      String currency = journalReferenceService.resolveCurrency(request.currency(), company);
      BigDecimal fxRate =
          journalReferenceService.resolveFxRate(currency, company, request.fxRate());
      String baseCurrency =
          StringUtils.hasText(company.getBaseCurrency())
              ? company.getBaseCurrency().trim().toUpperCase()
              : "INR";
      boolean foreignCurrency = !currency.equalsIgnoreCase(baseCurrency);

      JournalEntry entry = new JournalEntry();
      entry.setCompany(company);
      entry.setCurrency(currency);
      entry.setFxRate(fxRate);
      entry.setJournalType(resolveJournalEntryType(request.journalType()));
      entry.setSourceModule(normalizeSourceModule(request.sourceModule()));
      entry.setSourceReference(normalizeSourceReference(request.sourceReference()));
      entry.setReferenceNumber(
          journalReferenceService.resolveJournalReference(company, request.referenceNumber()));
      entry.setAttachmentReferences(joinAttachmentReferences(request.attachmentReferences()));
      auditMetadata.put("referenceNumber", entry.getReferenceNumber());
      acquireReferenceLock(company, entry.getReferenceNumber());

      Optional<JournalEntry> duplicate =
          journalEntryRepository.findByCompanyAndReferenceNumber(
              company, entry.getReferenceNumber());
      LocalDate entryDate = request.entryDate();
      if (entryDate == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Entry date is required");
      }
      boolean overrideRequested = Boolean.TRUE.equals(request.adminOverride());
      boolean overrideAuthorized =
          overrideRequested && periodValidationService.hasEntryDateOverrideAuthority();
      boolean manualJournal =
          entry.getJournalType() == JournalEntryType.MANUAL
              || "MANUAL".equalsIgnoreCase(entry.getSourceModule());
      if (manualJournal && !StringUtils.hasText(request.memo())) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Manual journal reason is required");
      }
      if (duplicate.isEmpty()) {
        AccountingPeriod postingPeriod;
        periodValidationService.validateEntryDate(
            company, entryDate, overrideRequested, overrideAuthorized);
        if (systemSettingsService.isPeriodLockEnforced()) {
          postingPeriod =
              accountingPeriodService.requirePostablePeriod(
                  company,
                  entryDate,
                  journalReferenceService.resolvePostingDocumentType(entry),
                  journalReferenceService.resolvePostingDocumentReference(entry),
                  request.memo(),
                  overrideAuthorized);
        } else {
          postingPeriod = accountingPeriodService.ensurePeriod(company, entryDate);
        }
        if (postingPeriod == null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Accounting period is required for journal posting");
        }
        entry.setAccountingPeriod(postingPeriod);
      }

      entry.setEntryDate(entryDate);
      entry.setMemo(request.memo());
      entry.setStatus(JournalEntryStatus.POSTED);
      Dealer dealer =
          request.dealerId() != null
              ? accountResolutionService.requireDealer(company, request.dealerId())
              : null;
      Supplier supplier =
          request.supplierId() != null
              ? accountResolutionService.requireSupplier(company, request.supplierId())
              : null;

      List<Long> sortedAccountIds =
          lines.stream()
              .map(JournalEntryRequest.JournalLineRequest::accountId)
              .filter(Objects::nonNull)
              .distinct()
              .sorted()
              .toList();
      Map<Long, Account> lockedAccounts = new HashMap<>();
      for (Long accountId : sortedAccountIds) {
        Account account =
            accountRepository
                .lockByCompanyAndId(company, accountId)
                .orElseThrow(
                    () ->
                        new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found"));
        lockedAccounts.put(accountId, account);
      }
      JournalPartnerContextService.ResolvedPartnerContext partnerContext =
          journalPartnerContextService.resolve(
              company, dealer, supplier, lockedAccounts.values().stream().distinct().toList());
      Account dealerReceivableAccount = partnerContext.dealerReceivableAccount();
      Account supplierPayableAccount = partnerContext.supplierPayableAccount();
      entry.setDealer(partnerContext.dealer());
      entry.setSupplier(partnerContext.supplier());

      if (partnerContext.hasReceivableAccount() && partnerContext.hasPayableAccount()) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Journal entry cannot combine AR and AP accounts; split into separate entries");
      }
      if (partnerContext.hasReceivableAccount() && dealer == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Posting to AR requires a dealer context");
      }
      if (partnerContext.hasPayableAccount() && supplier == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Posting to AP requires a supplier context");
      }

      Map<Account, BigDecimal> accountDeltas = new HashMap<>();
      List<JournalLine> postedLines = new ArrayList<>();
      BigDecimal totalBaseDebit = BigDecimal.ZERO;
      BigDecimal totalBaseCredit = BigDecimal.ZERO;
      BigDecimal totalForeignDebit = BigDecimal.ZERO;
      BigDecimal totalForeignCredit = BigDecimal.ZERO;
      JournalLinePostingService.RoundingAdjustment roundingAdjustment = null;
      int dealerArLines = 0;
      int supplierApLines = 0;
      for (JournalEntryRequest.JournalLineRequest lineRequest : lines) {
        JournalLine line =
            journalLinePostingService.buildPostedLine(entry, lineRequest, lockedAccounts, fxRate);
        postedLines.add(line);
        Account account = line.getAccount();
        accountDeltas.merge(account, line.getDebit().subtract(line.getCredit()), BigDecimal::add);
        totalBaseDebit = totalBaseDebit.add(line.getDebit());
        totalBaseCredit = totalBaseCredit.add(line.getCredit());
        if (foreignCurrency) {
          totalForeignDebit =
              totalForeignDebit.add(
                  lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit());
          totalForeignCredit =
              totalForeignCredit.add(
                  lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit());
        }
        if (dealerReceivableAccount != null
            && Objects.equals(account.getId(), dealerReceivableAccount.getId())) {
          dealerArLines++;
        }
        if (supplierPayableAccount != null
            && Objects.equals(account.getId(), supplierPayableAccount.getId())) {
          supplierApLines++;
        }
      }

      BigDecimal roundingDelta = totalBaseDebit.subtract(totalBaseCredit);
      if (roundingDelta.compareTo(BigDecimal.ZERO) != 0) {
        roundingAdjustment =
            journalLinePostingService.absorbRoundingDelta(
                roundingDelta, postedLines, accountDeltas);
        totalBaseDebit =
            postedLines.stream()
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalBaseCredit =
            postedLines.stream()
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
      }
      if (totalBaseDebit.subtract(totalBaseCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance");
      }
      if (foreignCurrency && totalForeignDebit.compareTo(BigDecimal.ZERO) > 0) {
        entry.setForeignAmountTotal(totalForeignDebit.setScale(2, RoundingMode.HALF_UP));
      }

      if (duplicate.isPresent()) {
        JournalEntry existingEntry = duplicate.get();
        if (existingEntry.getId() != null) {
          auditMetadata.put("journalEntryId", existingEntry.getId().toString());
        }
        journalDuplicateGuardService.ensureDuplicateMatchesExisting(
            existingEntry, entry, postedLines);
        auditMetadata.put("idempotent", "true");
        accountingAuditService.logAuditSuccessAfterCommit(
            AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
        return JournalEntryMutationOutcome.replayed(
            dtoMapperService.toJournalEntryDto(existingEntry));
      }

      if (dealer != null
          && dealerReceivableAccount != null
          && dealerArLines > 1
          && !overrideAuthorized) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Dealer journal entry has multiple receivable lines; admin override required");
      }
      if (supplier != null
          && supplierPayableAccount != null
          && supplierApLines > 1
          && !overrideAuthorized) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Supplier journal entry has multiple payable lines; admin override required");
      }

      var now = CompanyTime.now(company);
      String username = accountingAuditService.resolveCurrentUsername();
      entry.setCreatedAt(now);
      entry.setUpdatedAt(now);
      entry.setPostedAt(now);
      entry.setCreatedBy(username);
      entry.setLastModifiedBy(username);
      entry.setPostedBy(username);

      JournalEntry saved = journalEntryRepository.save(entry);

      boolean postedEventTrailRecorded = applyAccountDeltas(company, saved, accountDeltas);
      journalPartnerContextService.recordLedgerEntries(
          saved, dealerReceivableAccount, supplierPayableAccount);
      if (saved.getId() != null) {
        auditMetadata.put("journalEntryId", saved.getId().toString());
      }
      auditMetadata.put("status", saved.getStatus() != null ? saved.getStatus().name() : null);
      if (postedEventTrailRecorded) {
        accountingAuditService.logAuditSuccessAfterCommit(
            AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
      }
      if (accountingComplianceAuditService != null) {
        Map<String, String> fxRoundingMetadata =
            buildFxRoundingMetadata(roundingAdjustment, saved, company);
        accountingComplianceAuditService.recordJournalCreation(company, saved, fxRoundingMetadata);
      }
      if (closedPeriodPostingExceptionService != null
          && saved.getAccountingPeriod() != null
          && saved.getAccountingPeriod().getStatus() != AccountingPeriodStatus.OPEN) {
        closedPeriodPostingExceptionService.linkJournalEntry(
            company,
            journalReferenceService.resolvePostingDocumentType(saved),
            journalReferenceService.resolvePostingDocumentReference(saved),
            saved);
      }
      return JournalEntryMutationOutcome.created(dtoMapperService.toJournalEntryDto(saved));
    } catch (Exception e) {
      if (e.getMessage() != null) {
        auditMetadata.put("error", e.getMessage());
      }
      auditService.logFailure(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
      throw e;
    }
  }

  private boolean applyAccountDeltas(
      Company company, JournalEntry saved, Map<Account, BigDecimal> accountDeltas) {
    if (accountDeltas.isEmpty()) {
      return true;
    }
    List<Map.Entry<Account, BigDecimal>> sortedDeltas =
        accountDeltas.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getId()))
            .toList();
    Map<Long, BigDecimal> balancesBefore = new HashMap<>();
    for (Map.Entry<Account, BigDecimal> delta : sortedDeltas) {
      Account account = delta.getKey();
      BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
      if (account.getId() != null) {
        balancesBefore.putIfAbsent(account.getId(), current);
      }
      BigDecimal updated = current.add(delta.getValue());
      account.validateBalanceUpdate(updated);
      int rows = accountRepository.updateBalanceAtomic(company, account.getId(), delta.getValue());
      if (rows != 1) {
        throw new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Account balance update failed for " + account.getCode());
      }
    }
    for (Account account : accountDeltas.keySet()) {
      entityManager.detach(account);
    }
    accountingAuditService.publishAccountCacheInvalidated(company.getId());
    return accountingAuditService.recordJournalEntryPostedEventSafe(saved, balancesBefore);
  }

  private JournalEntryType resolveJournalEntryType(String journalType) {
    if (!StringUtils.hasText(journalType)) {
      return JournalEntryType.AUTOMATED;
    }
    try {
      return JournalEntryType.valueOf(journalType.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Unsupported journal type")
          .withDetail("journalType", journalType);
    }
  }

  private String normalizeSourceModule(String sourceModule) {
    return StringUtils.hasText(sourceModule) ? sourceModule.trim().toUpperCase() : null;
  }

  private String normalizeSourceReference(String sourceReference) {
    return StringUtils.hasText(sourceReference) ? sourceReference.trim() : null;
  }

  private String joinAttachmentReferences(List<String> attachmentReferences) {
    if (attachmentReferences == null || attachmentReferences.isEmpty()) {
      return null;
    }
    List<String> normalized =
        attachmentReferences.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    return normalized.isEmpty() ? null : String.join("\n", normalized);
  }

  private Map<String, String> buildFxRoundingMetadata(
      JournalLinePostingService.RoundingAdjustment roundingAdjustment,
      JournalEntry savedEntry,
      Company company) {
    if (roundingAdjustment == null || roundingAdjustment.adjustedLine() == null) {
      return Map.of();
    }
    Long adjustedLineId = resolveAdjustedLineId(roundingAdjustment, savedEntry);
    if (adjustedLineId == null) {
      throw new ApplicationException(
              ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
              "FX rounding adjustment metadata could not resolve persisted journal line id")
          .withDetail("referenceNumber", savedEntry.getReferenceNumber())
          .withDetail("companyId", company.getId());
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("adjustedLineId", adjustedLineId.toString());
    metadata.put("originalAmount", toPlainAmount(roundingAdjustment.originalAmount()));
    metadata.put("adjustedAmount", toPlainAmount(roundingAdjustment.adjustedAmount()));
    metadata.put("adjustmentReason", roundingAdjustment.adjustmentReason());
    return metadata;
  }

  private Long resolveAdjustedLineId(
      JournalLinePostingService.RoundingAdjustment roundingAdjustment, JournalEntry savedEntry) {
    JournalLine adjustedLine = roundingAdjustment.adjustedLine();
    if (adjustedLine.getId() != null) {
      return adjustedLine.getId();
    }

    Long matchedBeforeFlush =
        findMatchingAdjustedLineId(savedEntry, adjustedLine, roundingAdjustment);
    if (matchedBeforeFlush != null) {
      return matchedBeforeFlush;
    }

    entityManager.flush();
    if (adjustedLine.getId() != null) {
      return adjustedLine.getId();
    }

    return findMatchingAdjustedLineId(savedEntry, adjustedLine, roundingAdjustment);
  }

  private Long findMatchingAdjustedLineId(
      JournalEntry savedEntry,
      JournalLine adjustedLine,
      JournalLinePostingService.RoundingAdjustment roundingAdjustment) {
    if (savedEntry.getLines() == null || savedEntry.getLines().isEmpty()) {
      return null;
    }
    if (adjustedLine.getAccount() == null || adjustedLine.getAccount().getId() == null) {
      return null;
    }
    Long adjustedAccountId = adjustedLine.getAccount().getId();
    BigDecimal adjustedDebit = Objects.requireNonNullElse(adjustedLine.getDebit(), BigDecimal.ZERO);
    BigDecimal adjustedCredit =
        Objects.requireNonNullElse(adjustedLine.getCredit(), BigDecimal.ZERO);
    String adjustedDescription =
        StringUtils.hasText(adjustedLine.getDescription()) ? adjustedLine.getDescription() : null;

    for (JournalLine line : savedEntry.getLines()) {
      if (line.getId() == null || !matchesAdjustedAccount(line, adjustedAccountId)) {
        continue;
      }
      if (!equalsAmount(line.getDebit(), adjustedDebit)
          || !equalsAmount(line.getCredit(), adjustedCredit)) {
        continue;
      }
      if (adjustedDescription != null
          && !Objects.equals(line.getDescription(), adjustedDescription)) {
        continue;
      }
      return line.getId();
    }

    for (JournalLine line : savedEntry.getLines()) {
      if (line.getId() == null || !matchesAdjustedAccount(line, adjustedAccountId)) {
        continue;
      }
      if (equalsAmount(line.getDebit(), roundingAdjustment.adjustedAmount())
          || equalsAmount(line.getCredit(), roundingAdjustment.adjustedAmount())) {
        return line.getId();
      }
    }
    return null;
  }

  private boolean matchesAdjustedAccount(JournalLine line, Long adjustedAccountId) {
    return line.getAccount() != null && adjustedAccountId.equals(line.getAccount().getId());
  }

  private boolean equalsAmount(BigDecimal left, BigDecimal right) {
    BigDecimal normalizedLeft = Objects.requireNonNullElse(left, BigDecimal.ZERO);
    BigDecimal normalizedRight = Objects.requireNonNullElse(right, BigDecimal.ZERO);
    return normalizedLeft.compareTo(normalizedRight) == 0;
  }

  private String toPlainAmount(BigDecimal amount) {
    if (amount == null) {
      return "0";
    }
    return amount.stripTrailingZeros().toPlainString();
  }

  private void acquireReferenceLock(Company company, String referenceNumber) {
    if (company == null || company.getId() == null || !StringUtils.hasText(referenceNumber)) {
      return;
    }
    int companyKey = Long.hashCode(company.getId());
    int referenceKey = referenceNumber.trim().toUpperCase(Locale.ROOT).hashCode();
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(?1, ?2)")
        .setParameter(1, companyKey)
        .setParameter(2, referenceKey)
        .getSingleResult();
  }
}
