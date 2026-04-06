package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
public class CompanyScopedAccountingLookupService {

  private final CompanyScopedLookupService companyScopedLookupService;
  private final AccountRepository accountRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingPeriodRepository accountingPeriodRepository;

  @Autowired
  public CompanyScopedAccountingLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      AccountRepository accountRepository,
      JournalEntryRepository journalEntryRepository,
      AccountingPeriodRepository accountingPeriodRepository) {
    this.companyScopedLookupService = companyScopedLookupService;
    this.accountRepository = accountRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingPeriodRepository = accountingPeriodRepository;
  }

  public Account requireAccount(Company company, Long accountId) {
    return companyScopedLookupService.require(
        company, accountId, accountRepository::findByCompanyAndId, "Account");
  }

  public JournalEntry requireJournalEntry(Company company, Long journalEntryId) {
    return companyScopedLookupService.require(
        company, journalEntryId, journalEntryRepository::findByCompanyAndId, "Journal entry");
  }

  public AccountingPeriod requireAccountingPeriod(Company company, Long accountingPeriodId) {
    return companyScopedLookupService.require(
        company,
        accountingPeriodId,
        accountingPeriodRepository::findByCompanyAndId,
        "Accounting period");
  }
}
