package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;

@Service
class AccrualBadDebtPostingService {

  private final CompanyContextService companyContextService;
  private final AccountResolutionService accountResolutionService;
  private final JournalReferenceService journalReferenceService;
  private final JournalEntryService journalEntryService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final SettlementOutcomeService settlementOutcomeService;
  private final InvoiceRepository invoiceRepository;

  AccrualBadDebtPostingService(
      CompanyContextService companyContextService,
      AccountResolutionService accountResolutionService,
      JournalReferenceService journalReferenceService,
      JournalEntryService journalEntryService,
      CompanyScopedAccountingLookupService accountingLookupService,
      SettlementOutcomeService settlementOutcomeService,
      InvoiceRepository invoiceRepository) {
    this.companyContextService = companyContextService;
    this.accountResolutionService = accountResolutionService;
    this.journalReferenceService = journalReferenceService;
    this.journalEntryService = journalEntryService;
    this.accountingLookupService = accountingLookupService;
    this.settlementOutcomeService = settlementOutcomeService;
    this.invoiceRepository = invoiceRepository;
  }

  @Transactional
  JournalEntryDto postAccrual(AccrualRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String reference =
        journalReferenceService.resolveJournalReference(
            company,
            StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.referenceNumber());
    Account debit = accountResolutionService.requireAccount(company, request.debitAccountId());
    Account credit = accountResolutionService.requireAccount(company, request.creditAccountId());
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : accountResolutionService.currentDate(company);
    String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Accrual/Provision";
    BigDecimal amount = request.amount();
    JournalEntryDto accrual =
        journalEntryService.createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        debit.getId(), memo, amount, BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        credit.getId(), memo, BigDecimal.ZERO, amount))));
    if (request.autoReverseDate() != null) {
      JournalEntryDto reversal =
          journalEntryService.createJournalEntry(
              new JournalEntryRequest(
                  reference + "-REV",
                  request.autoReverseDate(),
                  "Reversal of " + reference,
                  null,
                  null,
                  request.adminOverride(),
                  List.of(
                      new JournalEntryRequest.JournalLineRequest(
                          credit.getId(), "Auto-reverse " + memo, amount, BigDecimal.ZERO),
                      new JournalEntryRequest.JournalLineRequest(
                          debit.getId(), "Auto-reverse " + memo, BigDecimal.ZERO, amount))));
      JournalEntry accrualJe = accountingLookupService.requireJournalEntry(company, accrual.id());
      JournalEntry reversalJe = accountingLookupService.requireJournalEntry(company, reversal.id());
      reversalJe.setReversalOf(accrualJe);
      accountingLookupService.requireJournalEntry(company, reversal.id());
    }
    return accrual;
  }

  @Transactional
  JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Invoice invoice =
        invoiceRepository
            .lockByCompanyAndId(company, request.invoiceId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
    Account ar = accountResolutionService.requireDealerReceivable(invoice.getDealer());
    Account expense = accountResolutionService.requireAccount(company, request.expenseAccountId());
    String reference =
        journalReferenceService.resolveJournalReference(
            company,
            StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.referenceNumber());
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : accountResolutionService.currentDate(company);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Bad debt write-off for invoice " + invoice.getInvoiceNumber();
    BigDecimal outstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
    BigDecimal amount = request.amount() != null ? request.amount() : outstanding;
    amount = ValidationUtils.requirePositive(amount, "amount");
    JournalEntryDto journalEntry =
        journalEntryService.createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                invoice.getDealer() != null ? invoice.getDealer().getId() : null,
                null,
                request.adminOverride(),
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        expense.getId(), memo, amount, BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        ar.getId(), memo, BigDecimal.ZERO, amount)),
                null,
                null,
                "BAD_DEBT_WRITE_OFF",
                reference,
                null,
                List.of()));
    JournalEntry saved = accountingLookupService.requireJournalEntry(company, journalEntry.id());
    settlementOutcomeService.applyBadDebtWriteOffToInvoice(
        invoice, amount, saved.getReferenceNumber() + "-BADDEBT", entryDate);
    return journalEntry;
  }
}
