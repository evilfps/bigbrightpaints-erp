package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

import jakarta.transaction.Transactional;

@Service
public class SupplierLedgerService
    extends AbstractPartnerLedgerService<Supplier, SupplierLedgerEntry> {

  private final SupplierLedgerRepository supplierLedgerRepository;
  private final SupplierRepository supplierRepository;
  private final CompanyContextService companyContextService;
  private final CompanyEntityLookup companyEntityLookup;

  public SupplierLedgerService(
      SupplierLedgerRepository supplierLedgerRepository,
      SupplierRepository supplierRepository,
      CompanyContextService companyContextService,
      CompanyEntityLookup companyEntityLookup) {
    this.supplierLedgerRepository = supplierLedgerRepository;
    this.supplierRepository = supplierRepository;
    this.companyContextService = companyContextService;
    this.companyEntityLookup = companyEntityLookup;
  }

  @Transactional
  public void recordLedgerEntry(Supplier supplier, LedgerContext context) {
    Objects.requireNonNull(supplier, "Partner is required for ledger entry");
    Objects.requireNonNull(context, "Ledger context is required");

    BigDecimal debit = normalizeAmount(context.debit());
    BigDecimal credit = normalizeAmount(context.credit());
    if (debit.compareTo(BigDecimal.ZERO) == 0 && credit.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }

    SupplierLedgerEntry entry = createEntry();
    populateEntry(entry, supplier, context, debit, credit);
    persistEntry(entry);
  }

  public Map<Long, BigDecimal> currentBalances(Collection<Long> supplierIds) {
    if (supplierIds == null || supplierIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Company company = companyContextService.requireCurrentCompany();
    List<SupplierBalanceView> aggregates =
        supplierLedgerRepository.aggregateBalances(company, supplierIds);
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
    Supplier supplier = companyEntityLookup.requireSupplier(company, supplierId);
    return supplierLedgerRepository
        .aggregateBalance(company, supplier)
        .map(SupplierBalanceView::balance)
        .orElse(BigDecimal.ZERO);
  }

  @Override
  protected SupplierLedgerEntry createEntry() {
    return new SupplierLedgerEntry();
  }

  @Override
  protected void persistEntry(SupplierLedgerEntry entry) {
    supplierLedgerRepository.save(entry);
  }

  @Override
  protected Supplier reloadPartner(Supplier partner) {
    return supplierRepository
        .lockByCompanyAndId(partner.getCompany(), partner.getId())
        .orElse(partner);
  }

  @Override
  protected BigDecimal aggregateBalance(Supplier partner) {
    return supplierLedgerRepository
        .aggregateBalance(partner.getCompany(), partner)
        .map(SupplierBalanceView::balance)
        .orElse(BigDecimal.ZERO);
  }

  @Override
  protected void updateOutstandingBalance(Supplier partner, BigDecimal balance) {
    // Supplier balance is ledger-derived read-model data only; do not persist a supplier-row cache.
  }

  @Override
  protected void populateEntry(
      SupplierLedgerEntry entry,
      Supplier partner,
      LedgerContext context,
      BigDecimal debit,
      BigDecimal credit) {
    entry.setCompany(partner.getCompany());
    entry.setSupplier(partner);
    entry.setEntryDate(context.entryDate());
    entry.setReferenceNumber(context.referenceNumber());
    entry.setMemo(context.memo());
    entry.setJournalEntry(context.journalEntry());
    entry.setDebit(debit);
    entry.setCredit(credit);
  }

  private BigDecimal normalizeAmount(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount;
  }
}
