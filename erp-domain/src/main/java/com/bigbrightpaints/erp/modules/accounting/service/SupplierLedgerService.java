package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SupplierLedgerService {

    private final SupplierLedgerRepository supplierLedgerRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyContextService companyContextService;

    public SupplierLedgerService(SupplierLedgerRepository supplierLedgerRepository,
                                 SupplierRepository supplierRepository,
                                 CompanyContextService companyContextService) {
        this.supplierLedgerRepository = supplierLedgerRepository;
        this.supplierRepository = supplierRepository;
        this.companyContextService = companyContextService;
    }

    public void recordLedgerEntry(Supplier supplier, LocalLedgerContext context) {
        Objects.requireNonNull(supplier, "Supplier is required for ledger entry");
        BigDecimal debit = context.debit() == null ? BigDecimal.ZERO : context.debit();
        BigDecimal credit = context.credit() == null ? BigDecimal.ZERO : context.credit();
        if (debit.compareTo(BigDecimal.ZERO) == 0 && credit.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        SupplierLedgerEntry entry = new SupplierLedgerEntry();
        entry.setCompany(supplier.getCompany());
        entry.setSupplier(supplier);
        entry.setEntryDate(context.entryDate());
        entry.setReferenceNumber(context.referenceNumber());
        entry.setMemo(context.memo());
        entry.setJournalEntry(context.journalEntry());
        entry.setDebit(debit);
        entry.setCredit(credit);
        supplierLedgerRepository.save(entry);

        Supplier managed = supplierRepository.findById(supplier.getId()).orElse(supplier);
        BigDecimal aggregate = supplierLedgerRepository.aggregateBalance(managed.getCompany(), managed)
                .map(SupplierBalanceView::balance)
                .orElse(BigDecimal.ZERO);
        managed.setOutstandingBalance(aggregate);
        supplierRepository.save(managed);
    }

    public Map<Long, BigDecimal> currentBalances(Collection<Long> supplierIds) {
        if (supplierIds == null || supplierIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Company company = companyContextService.requireCurrentCompany();
        List<SupplierBalanceView> aggregates = supplierLedgerRepository.aggregateBalances(company, supplierIds);
        Map<Long, BigDecimal> result = new HashMap<>();
        for (SupplierBalanceView aggregate : aggregates) {
            result.put(aggregate.supplierId(), aggregate.balance());
        }
        return result;
    }

    public BigDecimal currentBalance(Long supplierId) {
        if (supplierId == null) {
            return BigDecimal.ZERO;
        }
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
        return supplierLedgerRepository.aggregateBalance(company, supplier)
                .map(SupplierBalanceView::balance)
                .orElse(BigDecimal.ZERO);
    }

    public record LocalLedgerContext(LocalDate entryDate,
                                     String referenceNumber,
                                     String memo,
                                     BigDecimal debit,
                                     BigDecimal credit,
                                     JournalEntry journalEntry) {}
}
