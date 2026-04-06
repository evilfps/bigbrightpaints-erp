package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
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

  private final CompanyEntityLookup legacyLookup;
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
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.accountRepository = accountRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingPeriodRepository = accountingPeriodRepository;
  }

  private CompanyScopedAccountingLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.accountRepository = null;
    this.journalEntryRepository = null;
    this.accountingPeriodRepository = null;
  }

  public static CompanyScopedAccountingLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedAccountingLookupService(legacyLookup);
  }

  public Account requireAccount(Company company, Long accountId) {
    if (legacyLookup != null) {
      return legacyLookup.requireAccount(company, accountId);
    }
    return companyScopedLookupService.require(
        company, accountId, accountRepository::findByCompanyAndId, "Account");
  }

  public JournalEntry requireJournalEntry(Company company, Long journalEntryId) {
    if (legacyLookup != null) {
      return legacyLookup.requireJournalEntry(company, journalEntryId);
    }
    return companyScopedLookupService.require(
        company, journalEntryId, journalEntryRepository::findByCompanyAndId, "Journal entry");
  }

  public AccountingPeriod requireAccountingPeriod(Company company, Long accountingPeriodId) {
    if (legacyLookup != null) {
      return legacyLookup.requireAccountingPeriod(company, accountingPeriodId);
    }
    return companyScopedLookupService.require(
        company,
        accountingPeriodId,
        accountingPeriodRepository::findByCompanyAndId,
        "Accounting period");
  }
}
