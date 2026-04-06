package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

public final class AccountingFacadeTestFactory {

  private AccountingFacadeTestFactory() {}

  public static AccountingFacade create(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingService accountingService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    return create(
        companyContextService,
        accountRepository,
        accountingService,
        org.mockito.Mockito.mock(PayrollAccountingService.class),
        journalEntryRepository,
        referenceNumberService,
        dealerRepository,
        supplierRepository,
        companyClock,
        salesLookupService,
        accountingLookupService,
        companyAccountingSettingsService,
        journalReferenceResolver,
        journalReferenceMappingRepository);
  }

  public static AccountingFacade create(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingService accountingService,
      DealerReceiptService dealerReceiptService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    return create(
        companyContextService,
        accountRepository,
        accountingService,
        org.mockito.Mockito.mock(PayrollAccountingService.class),
        dealerReceiptService,
        journalEntryRepository,
        referenceNumberService,
        dealerRepository,
        supplierRepository,
        companyClock,
        salesLookupService,
        accountingLookupService,
        companyAccountingSettingsService,
        journalReferenceResolver,
        journalReferenceMappingRepository);
  }

  public static AccountingFacade create(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingService accountingService,
      PayrollAccountingService payrollAccountingService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    return create(
        companyContextService,
        accountRepository,
        accountingService,
        payrollAccountingService,
        org.mockito.Mockito.mock(DealerReceiptService.class),
        journalEntryRepository,
        referenceNumberService,
        dealerRepository,
        supplierRepository,
        companyClock,
        salesLookupService,
        accountingLookupService,
        companyAccountingSettingsService,
        journalReferenceResolver,
        journalReferenceMappingRepository);
  }

  public static AccountingFacade create(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingService accountingService,
      PayrollAccountingService payrollAccountingService,
      DealerReceiptService dealerReceiptService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    AccountingFacadeAccountResolver accountResolver =
        new AccountingFacadeAccountResolver(accountingLookupService);
    AccountingFacadeTaxSupport taxSupport =
        new AccountingFacadeTaxSupport(companyAccountingSettingsService);
    return new AccountingFacade(
        accountingService,
        dealerReceiptService,
        new SalesJournalFacadeOperations(
            companyContextService,
            accountingService,
            companyClock,
            salesLookupService,
            taxSupport,
            journalReferenceResolver,
            journalEntryRepository,
            journalReferenceMappingRepository,
            accountingLookupService),
        new SalesReturnJournalFacadeOperations(
            companyContextService,
            accountingService,
            companyClock,
            salesLookupService,
            journalReferenceResolver,
            journalEntryRepository,
            journalReferenceMappingRepository),
        new PurchaseJournalFacadeOperations(
            companyContextService,
            accountingService,
            journalEntryRepository,
            referenceNumberService,
            supplierRepository,
            companyClock,
            accountingLookupService,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            taxSupport),
        new FactoryJournalFacadeOperations(
            companyContextService,
            accountingService,
            journalEntryRepository,
            companyClock,
            journalReferenceResolver,
            accountResolver),
        new InventoryAdjustmentFacadeOperations(
            companyContextService,
            accountingService,
            referenceNumberService,
            companyClock,
            journalEntryRepository,
            accountResolver),
        new ManualJournalFacadeOperations(accountingService, companyContextService, companyClock),
        accountResolver,
        payrollAccountingService);
  }
}
