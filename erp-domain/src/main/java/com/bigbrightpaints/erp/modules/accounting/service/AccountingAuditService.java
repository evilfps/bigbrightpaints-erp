package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AuditDigestResponse;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

import java.time.LocalDate;

@Service
public class AccountingAuditService extends AccountingCoreEngine {

    @Autowired
    public AccountingAuditService(CompanyContextService companyContextService,
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
        super(companyContextService,
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

    public AuditDigestResponse auditDigest(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "from date must be on or before to date");
        }
        return super.auditDigest(from, to);
    }

    public String auditDigestCsv(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "from date must be on or before to date");
        }
        return super.auditDigestCsv(from, to);
    }
}
