package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountingFacade extends AccountingFacadeCore {

    public static final String MANUAL_REFERENCE_PREFIX = AccountingFacadeCore.MANUAL_REFERENCE_PREFIX;

    public AccountingFacade(CompanyContextService companyContextService,
                            AccountRepository accountRepository,
                            AccountingService accountingService,
                            JournalEntryRepository journalEntryRepository,
                            ReferenceNumberService referenceNumberService,
                            DealerRepository dealerRepository,
                            SupplierRepository supplierRepository,
                            CompanyClock companyClock,
                            CompanyEntityLookup companyEntityLookup,
                            CompanyAccountingSettingsService companyAccountingSettingsService,
                            JournalReferenceResolver journalReferenceResolver,
                            JournalReferenceMappingRepository journalReferenceMappingRepository) {
        super(companyContextService,
                accountRepository,
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                dealerRepository,
                supplierRepository,
                companyClock,
                companyEntityLookup,
                companyAccountingSettingsService,
                journalReferenceResolver,
                journalReferenceMappingRepository);
    }

    public static boolean isReservedReferenceNamespace(String referenceNumber) {
        return AccountingFacadeCore.isReservedReferenceNamespace(referenceNumber);
    }

    @Override
    public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
        // return accountingService.recordPayrollPayment(request);
        return super.recordPayrollPayment(request);
    }
}
