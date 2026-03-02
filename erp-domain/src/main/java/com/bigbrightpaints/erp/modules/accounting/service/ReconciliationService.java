package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService extends ReconciliationServiceCore {

    public ReconciliationService(CompanyContextService companyContextService,
                                 CompanyRepository companyRepository,
                                 AccountRepository accountRepository,
                                 DealerRepository dealerRepository,
                                 DealerLedgerRepository dealerLedgerRepository,
                                 SupplierRepository supplierRepository,
                                 SupplierLedgerRepository supplierLedgerRepository,
                                 InventoryReservationRepository inventoryReservationRepository,
                                 PackagingSlipRepository packagingSlipRepository,
                                 SalesOrderRepository salesOrderRepository,
                                 JournalEntryRepository journalEntryRepository,
                                 JournalLineRepository journalLineRepository,
                                 TemporalBalanceService temporalBalanceService) {
        super(companyContextService,
                companyRepository,
                accountRepository,
                dealerRepository,
                dealerLedgerRepository,
                supplierRepository,
                supplierLedgerRepository,
                inventoryReservationRepository,
                packagingSlipRepository,
                salesOrderRepository,
                journalEntryRepository,
                journalLineRepository,
                temporalBalanceService);
    }
}
