package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.persistence.EntityManager;

@Service
public class JournalEntryService extends AccountingCoreEngineCore {

  private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = BigDecimal.ZERO;

  @Autowired
  public JournalEntryService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      JournalEntryRepository journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      AccountingPeriodService accountingPeriodService,
      ReferenceNumberService referenceNumberService,
      ApplicationEventPublisher eventPublisher,
      CompanyClock companyClock,
      CompanyEntityLookup companyEntityLookup,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      InvoiceRepository invoiceRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      InvoiceSettlementPolicy invoiceSettlementPolicy,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository,
      EntityManager entityManager,
      SystemSettingsService systemSettingsService,
      AuditService auditService,
      AccountingEventStore accountingEventStore) {
    super(
        companyContextService,
        accountRepository,
        journalEntryRepository,
        dealerLedgerService,
        supplierLedgerService,
        payrollRunRepository,
        payrollRunLineRepository,
        accountingPeriodService,
        referenceNumberService,
        eventPublisher,
        companyClock,
        companyEntityLookup,
        settlementAllocationRepository,
        rawMaterialPurchaseRepository,
        invoiceRepository,
        rawMaterialMovementRepository,
        rawMaterialBatchRepository,
        finishedGoodBatchRepository,
        dealerRepository,
        supplierRepository,
        invoiceSettlementPolicy,
        journalReferenceResolver,
        journalReferenceMappingRepository,
        entityManager,
        systemSettingsService,
        auditService,
        accountingEventStore);
  }

  @Override
  public List<JournalEntryDto> listJournalEntries(
      Long dealerId, Long supplierId, int page, int size) {
    return super.listJournalEntries(dealerId, supplierId, page, size);
  }

  @Override
  public List<JournalEntryDto> listJournalEntries(Long dealerId) {
    return super.listJournalEntries(dealerId);
  }

  @Override
  public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
    return super.listJournalEntriesByReferencePrefix(prefix);
  }

  @Retryable(value = DataIntegrityViolationException.class, maxAttempts = 3, backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  @Override
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
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
      String currency = resolveCurrency(request.currency(), company);
      BigDecimal fxRate = resolveFxRate(currency, company, request.fxRate());
      String baseCurrency =
          company.getBaseCurrency() != null && !company.getBaseCurrency().isBlank()
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
      entry.setReferenceNumber(resolveJournalReference(company, request.referenceNumber()));
      auditMetadata.put("referenceNumber", entry.getReferenceNumber());

      Optional<JournalEntry> duplicate =
          journalEntryRepository.findByCompanyAndReferenceNumber(company, entry.getReferenceNumber());

      LocalDate entryDate = request.entryDate();
      if (entryDate == null) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date is required"); }
      boolean overrideRequested = Boolean.TRUE.equals(request.adminOverride());
      boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
      boolean manualJournal =
          entry.getJournalType() == JournalEntryType.MANUAL
              || "MANUAL".equalsIgnoreCase(entry.getSourceModule());
      if (manualJournal && !StringUtils.hasText(request.memo())) { throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Manual journal reason is required"); }
      entry.setAttachmentReferences(joinAttachmentReferences(request.attachmentReferences()));
      if (duplicate.isEmpty()) {
        AccountingPeriod postingPeriod;
        if (systemSettingsService.isPeriodLockEnforced()) {
          validateEntryDate(company, entryDate, overrideRequested, overrideAuthorized);
          postingPeriod =
              accountingPeriodService.requirePostablePeriod(
                  company,
                  entryDate,
                  resolvePostingDocumentType(entry),
                  resolvePostingDocumentReference(entry),
                  request.memo(),
                  overrideAuthorized);
        } else {
          validateEntryDate(company, entryDate, overrideRequested, overrideAuthorized);
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
      entry.setStatus("POSTED");
      Dealer dealer = null;
      Account dealerReceivableAccount = null;
      Supplier supplier = null;
      Account supplierPayableAccount = null;
      if (request.dealerId() != null) {
        dealer = requireDealer(company, request.dealerId());
        dealerReceivableAccount = dealer.getReceivableAccount();
        entry.setDealer(dealer);
      }
      if (request.supplierId() != null) {
        supplier = requireSupplier(company, request.supplierId());
        supplierPayableAccount = supplier.getPayableAccount();
        entry.setSupplier(supplier);
      }
      Map<Account, BigDecimal> accountDeltas = new HashMap<>();
      BigDecimal dealerLedgerDebitTotal = BigDecimal.ZERO;
      BigDecimal dealerLedgerCreditTotal = BigDecimal.ZERO;
      BigDecimal supplierLedgerDebitTotal = BigDecimal.ZERO;
      BigDecimal supplierLedgerCreditTotal = BigDecimal.ZERO;
      int dealerArLines = 0;
      int supplierApLines = 0;
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
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found"));
        lockedAccounts.put(accountId, account);
      }
      Dealer dealerContext = dealer;
      Supplier supplierContext = supplier;
      boolean hasReceivableAccount = false;
      boolean hasPayableAccount = false;
      List<Account> distinctAccounts = lockedAccounts.values().stream().distinct().toList();
      Map<Long, Set<Long>> dealerOwnerByReceivableAccountId = new HashMap<>();
      Map<Long, Set<Long>> supplierOwnerByPayableAccountId = new HashMap<>();
      List<Account> receivableAccounts =
          distinctAccounts.stream().filter(this::isReceivableAccount).toList();
      if (!receivableAccounts.isEmpty()) {
        hasReceivableAccount = true;
        List<Dealer> dealerOwners =
            dealerRepository.findByCompanyAndReceivableAccountIn(company, receivableAccounts);
        for (Dealer owner : dealerOwners) {
          if (owner.getReceivableAccount() == null || owner.getReceivableAccount().getId() == null || owner.getId() == null) { continue; }
          dealerOwnerByReceivableAccountId
              .computeIfAbsent(owner.getReceivableAccount().getId(), ignored -> new HashSet<>())
              .add(owner.getId());
        }
      }
      List<Account> payableAccounts = distinctAccounts.stream().filter(this::isPayableAccount).toList();
      if (!payableAccounts.isEmpty()) {
        hasPayableAccount = true;
        List<Supplier> supplierOwners =
            supplierRepository.findByCompanyAndPayableAccountIn(company, payableAccounts);
        for (Supplier owner : supplierOwners) {
          if (owner.getPayableAccount() == null || owner.getPayableAccount().getId() == null || owner.getId() == null) { continue; }
          supplierOwnerByPayableAccountId
              .computeIfAbsent(owner.getPayableAccount().getId(), ignored -> new HashSet<>())
              .add(owner.getId());
        }
      }
      for (Account account : distinctAccounts) {
        Long accountId = account.getId();
        if (accountId == null) { continue; }
        Set<Long> dealerOwnerIds = dealerOwnerByReceivableAccountId.get(accountId);
        if (dealerOwnerIds != null && !dealerOwnerIds.isEmpty()) {
          if (dealerContext == null) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Dealer receivable account " + account.getCode() + " requires a dealer context");
          }
          Long dealerContextId = dealerContext.getId();
          if (dealerContextId == null || !dealerOwnerIds.contains(dealerContextId)) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Dealer receivable account "
                    + account.getCode()
                    + " requires matching dealer context");
          }
        }
        Set<Long> supplierOwnerIds = supplierOwnerByPayableAccountId.get(accountId);
        if (supplierOwnerIds != null && !supplierOwnerIds.isEmpty()) {
          if (supplierContext == null) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Supplier payable account " + account.getCode() + " requires a supplier context");
          }
          Long supplierContextId = supplierContext.getId();
          if (supplierContextId == null || !supplierOwnerIds.contains(supplierContextId)) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Supplier payable account "
                    + account.getCode()
                    + " requires matching supplier context");
          }
        }
      }
      if (hasReceivableAccount && hasPayableAccount) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Journal entry cannot combine AR and AP accounts; split into separate entries");
      }
      if (hasReceivableAccount && dealerContext == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Posting to AR requires a dealer context");
      }
      if (hasPayableAccount && supplierContext == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Posting to AP requires a supplier context");
      }
      if (dealerContext != null && hasReceivableAccount && dealerReceivableAccount == null) { dealerReceivableAccount = requireDealerReceivable(dealerContext); }
      if (supplierContext != null && hasPayableAccount && supplierPayableAccount == null) { supplierPayableAccount = requireSupplierPayable(supplierContext); }
      BigDecimal totalBaseDebit = BigDecimal.ZERO;
      BigDecimal totalBaseCredit = BigDecimal.ZERO;
      BigDecimal totalForeignDebit = BigDecimal.ZERO;
      BigDecimal totalForeignCredit = BigDecimal.ZERO;
      List<JournalLine> postedLines = new ArrayList<>();
      for (JournalEntryRequest.JournalLineRequest lineRequest : lines) {
        if (lineRequest.accountId() == null) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Account is required for every journal line"); }
        Account account = lockedAccounts.get(lineRequest.accountId());
        if (account == null) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found"); }

        JournalLine line = new JournalLine();
        line.setJournalEntry(entry);
        line.setAccount(account);
        line.setDescription(lineRequest.description());

        BigDecimal debitInput = lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit();
        BigDecimal creditInput =
            lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit();
        if (debitInput.compareTo(BigDecimal.ZERO) < 0 || creditInput.compareTo(BigDecimal.ZERO) < 0) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit/Credit cannot be negative"); }
        if (debitInput.compareTo(BigDecimal.ZERO) > 0 && creditInput.compareTo(BigDecimal.ZERO) > 0) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit and credit cannot both be non-zero on the same line"); }

        if (foreignCurrency) {
          totalForeignDebit = totalForeignDebit.add(debitInput);
          totalForeignCredit = totalForeignCredit.add(creditInput);
        }

        BigDecimal baseDebit = toBaseCurrency(debitInput, fxRate);
        BigDecimal baseCredit = toBaseCurrency(creditInput, fxRate);
        line.setDebit(baseDebit);
        line.setCredit(baseCredit);
        entry.addLine(line);
        postedLines.add(line);
        accountDeltas.merge(account, baseDebit.subtract(baseCredit), BigDecimal::add);
        totalBaseDebit = totalBaseDebit.add(baseDebit);
        totalBaseCredit = totalBaseCredit.add(baseCredit);

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
        if (roundingDelta.abs().compareTo(new BigDecimal("0.05")) > 0) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance")
              .withDetail("delta", roundingDelta)
              .withDetail("currency", currency)
              .withDetail("fxRate", fxRate);
        }
        if (roundingDelta.signum() > 0) {
          JournalLine target =
              postedLines.stream()
                  .filter(line -> line.getCredit().compareTo(BigDecimal.ZERO) > 0)
                  .max(Comparator.comparing(JournalLine::getCredit))
                  .orElse(null);
          if (target != null) {
            target.setCredit(target.getCredit().add(roundingDelta));
            accountDeltas.merge(target.getAccount(), roundingDelta.negate(), BigDecimal::add);
            totalBaseCredit = totalBaseCredit.add(roundingDelta);
          }
        } else {
          BigDecimal adjust = roundingDelta.abs();
          JournalLine target =
              postedLines.stream()
                  .filter(line -> line.getDebit().compareTo(BigDecimal.ZERO) > 0)
                  .max(Comparator.comparing(JournalLine::getDebit))
                  .orElse(null);
          if (target != null) {
            target.setDebit(target.getDebit().add(adjust));
            accountDeltas.merge(target.getAccount(), adjust, BigDecimal::add);
            totalBaseDebit = totalBaseDebit.add(adjust);
          }
        }
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
        ensureDuplicateMatchesExisting(existingEntry, entry, postedLines);
        log.info("Idempotent return: journal entry already exists, returning existing entry");
        auditMetadata.put("idempotent", "true");
        logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
        return toDto(existingEntry);
      }

      if (dealer != null && dealerReceivableAccount != null && dealerArLines > 1 && !overrideAuthorized) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Dealer journal entry has multiple receivable lines; admin override required");
      }
      if (supplier != null && supplierPayableAccount != null && supplierApLines > 1 && !overrideAuthorized) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Supplier journal entry has multiple payable lines; admin override required");
      }
      Instant now = CompanyTime.now(company);
      String username = resolveCurrentUsername();
      entry.setCreatedAt(now);
      entry.setUpdatedAt(now);
      entry.setPostedAt(now);
      entry.setCreatedBy(username);
      entry.setLastModifiedBy(username);
      entry.setPostedBy(username);
      JournalEntry saved;
      try {
        saved = journalEntryRepository.save(entry);
      } catch (DataIntegrityViolationException ex) {
        Optional<JournalEntry> existing =
            journalEntryRepository.findByCompanyAndReferenceNumber(company, entry.getReferenceNumber());
        if (existing.isPresent()) {
          JournalEntry existingEntry = existing.get();
          if (existingEntry.getId() != null) {
            auditMetadata.put("journalEntryId", existingEntry.getId().toString());
          }
          ensureDuplicateMatchesExisting(existingEntry, entry, postedLines);
          log.info(
              "Idempotent return after concurrent save race: journal entry already exists,"
                  + " returning existing entry");
          auditMetadata.put("idempotent", "true");
          logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
          return toDto(existingEntry);
        }
        log.info("Concurrent journal save conflict detected; retrying in fresh transaction");
        throw ex;
      }
      boolean postedEventTrailRecorded = true;
      if (!accountDeltas.isEmpty()) {
        List<Map.Entry<Account, BigDecimal>> sortedDeltas =
            accountDeltas.entrySet().stream()
                .sorted(Comparator.comparing(entryDelta -> entryDelta.getKey().getId()))
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
        publishAccountCacheInvalidated(company.getId());
        postedEventTrailRecorded = recordJournalEntryPostedEventSafe(saved, balancesBefore);
      }
      if (saved.getDealer() != null && dealerReceivableAccount != null) {
        for (JournalLine line : saved.getLines()) {
          if (line.getAccount() != null
              && Objects.equals(line.getAccount().getId(), dealerReceivableAccount.getId())) {
            dealerLedgerDebitTotal = dealerLedgerDebitTotal.add(line.getDebit());
            dealerLedgerCreditTotal = dealerLedgerCreditTotal.add(line.getCredit());
          }
        }
        if (dealerLedgerDebitTotal.compareTo(BigDecimal.ZERO) != 0
            || dealerLedgerCreditTotal.compareTo(BigDecimal.ZERO) != 0) {
          dealerLedgerService.recordLedgerEntry(
              saved.getDealer(), new AbstractPartnerLedgerService.LedgerContext(
                  saved.getEntryDate(), saved.getReferenceNumber(), saved.getMemo(), dealerLedgerDebitTotal, dealerLedgerCreditTotal, saved));
        }
      }
      if (saved.getSupplier() != null && supplierPayableAccount != null) {
        for (JournalLine line : saved.getLines()) {
          if (line.getAccount() != null
              && Objects.equals(line.getAccount().getId(), supplierPayableAccount.getId())) {
            supplierLedgerDebitTotal = supplierLedgerDebitTotal.add(line.getDebit());
            supplierLedgerCreditTotal = supplierLedgerCreditTotal.add(line.getCredit());
          }
        }
        if (supplierLedgerDebitTotal.compareTo(BigDecimal.ZERO) != 0
            || supplierLedgerCreditTotal.compareTo(BigDecimal.ZERO) != 0) {
          supplierLedgerService.recordLedgerEntry(
              saved.getSupplier(), new AbstractPartnerLedgerService.LedgerContext(
                  saved.getEntryDate(), saved.getReferenceNumber(), saved.getMemo(), supplierLedgerDebitTotal, supplierLedgerCreditTotal, saved));
        }
      }
      if (saved.getId() != null) {
        auditMetadata.put("journalEntryId", saved.getId().toString());
      }
      auditMetadata.put("status", saved.getStatus());
      if (postedEventTrailRecorded) {
        logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
      }
      if (accountingComplianceAuditService != null) {
        accountingComplianceAuditService.recordJournalCreation(company, saved);
      }
      if (closedPeriodPostingExceptionService != null
          && saved.getAccountingPeriod() != null
          && saved.getAccountingPeriod().getStatus() != AccountingPeriodStatus.OPEN) {
        closedPeriodPostingExceptionService.linkJournalEntry(
            company,
            resolvePostingDocumentType(saved),
            resolvePostingDocumentReference(saved),
            saved);
      }
      return toDto(saved);
    } catch (Exception e) {
      if (e.getMessage() != null) {
        auditMetadata.put("error", e.getMessage());
      }
      auditService.logFailure(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
      throw e;
    }
  }

  @Override
  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
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
    if (resolvedLines == null || resolvedLines.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "At least one journal line is required");
    }

    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    for (JournalEntryRequest.JournalLineRequest line : resolvedLines) {
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

    LocalDate entryDate = resolveManualEntryDate(request.entryDate());
    String narration = request.narration().trim();
    String sourceModule = request.sourceModule().trim();
    String sourceReference = request.sourceReference().trim();
    boolean manualSource = "MANUAL".equalsIgnoreCase(sourceModule);
    JournalEntryRequest journalRequest =
        new JournalEntryRequest(
            manualSource ? null : sourceReference,
            entryDate,
            narration,
            request.dealerId(),
            request.supplierId(),
            Boolean.TRUE.equals(request.adminOverride()),
            resolvedLines,
            null,
            null,
            sourceModule,
            sourceReference,
            manualSource ? JournalEntryType.MANUAL.name() : JournalEntryType.AUTOMATED.name(),
            request.attachmentReferences());
    if (manualSource) {
      return createManualJournalEntry(journalRequest, sourceReference);
    }
    return createJournalEntry(journalRequest);
  }

  private LocalDate resolveManualEntryDate(LocalDate requestedEntryDate) {
    if (requestedEntryDate != null) {
      return requestedEntryDate;
    }
    return companyClock.today(companyContextService.requireCurrentCompany());
  }

  @Override
  public PageResponse<JournalListItemDto> listJournals(
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
    return super.listJournals(fromDate, toDate, journalType, sourceModule, page, size);
  }

  @Transactional
  public JournalEntryDto createManualJournalEntry(
      JournalEntryRequest request, String idempotencyKey) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Journal entry request is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    String rawKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
    String key = StringUtils.hasText(rawKey) ? normalizeIdempotencyMappingKey(rawKey) : null;
    if (StringUtils.hasText(rawKey)) {
      Optional<JournalEntry> existingByReference =
          journalEntryRepository.findByCompanyAndReferenceNumber(company, rawKey);
      if (existingByReference.isPresent()) {
        return toDto(existingByReference.get());
      }
      Optional<JournalEntry> existingByResolver =
          journalReferenceResolver.findExistingEntry(company, rawKey);
      if (existingByResolver.isPresent()) {
        return toDto(existingByResolver.get());
      }
      int reserved =
          journalReferenceMappingRepository.reserveManualReference(
              company.getId(),
              key,
              reservedManualReference(key),
              "JOURNAL_ENTRY",
              CompanyTime.now(company));
      if (reserved == 0) { JournalEntry already = awaitJournalEntry(company, rawKey, key); if (already != null) { return toDto(already); } throw new ApplicationException(
              ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
              "Manual journal idempotency key already reserved but entry not found").withDetail("referenceNumber", rawKey); }
    }
    JournalEntryDto created;
    try {
      created =
          createJournalEntry(new JournalEntryRequest(
              null,
              request.entryDate(),
              request.memo(),
              request.dealerId(),
              request.supplierId(),
              request.adminOverride(),
              request.lines(),
              request.currency(),
              request.fxRate(),
              request.sourceModule(),
              request.sourceReference(),
              StringUtils.hasText(request.journalType()) ? request.journalType() : JournalEntryType.MANUAL.name(),
              request.attachmentReferences()));
    } catch (RuntimeException ex) {
      if (!StringUtils.hasText(rawKey) || !isRetryableManualConcurrencyFailure(ex)) {
        throw ex;
      }
      JournalEntry already = awaitJournalEntry(company, rawKey, key);
      if (already != null) {
        return toDto(already);
      }
      throw ex;
    }
    if (StringUtils.hasText(key) && created != null && StringUtils.hasText(created.referenceNumber())) {
      JournalReferenceMapping mapping =
          findLatestLegacyReferenceMapping(company, key)
              .orElseThrow(() -> new ApplicationException(
                      ErrorCode.INTERNAL_CONCURRENCY_FAILURE, "Manual journal idempotency reservation missing")
                  .withDetail("referenceNumber", rawKey));
      mapping.setCanonicalReference(created.referenceNumber());
      mapping.setEntityId(created.id());
      journalReferenceMappingRepository.save(mapping);
    }
    return created;
  }

  @Override
  public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
    if (request != null
        && (request.cascadeRelatedEntries()
            || (request.relatedEntryIds() != null && !request.relatedEntryIds().isEmpty()))) {
      List<JournalEntryDto> reversedEntries = super.cascadeReverseRelatedEntries(entryId, request);
      if (!reversedEntries.isEmpty()) {
        return reversedEntries.getFirst();
      }
    }
    return super.reverseJournalEntry(entryId, request);
  }

  @Override
  JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    return super.reverseClosingEntryForPeriodReopen(entry, period, reason);
  }

  @Override
  public List<JournalEntryDto> cascadeReverseRelatedEntries(
      Long primaryEntryId, JournalEntryReversalRequest request) {
    return super.cascadeReverseRelatedEntries(primaryEntryId, request);
  }
}
