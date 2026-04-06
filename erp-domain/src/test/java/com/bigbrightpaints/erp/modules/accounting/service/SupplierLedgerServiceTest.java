package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;

@ExtendWith(MockitoExtension.class)
class SupplierLedgerServiceTest {

  @Mock private SupplierLedgerRepository supplierLedgerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;

  private SupplierLedgerService supplierLedgerService;

  @BeforeEach
  void setUp() {
    supplierLedgerService =
        new SupplierLedgerService(
            supplierLedgerRepository,
            supplierRepository,
            companyContextService,
            purchasingLookupService);
  }

  @Test
  void recordLedgerEntry_persistsEntryWithoutSupplierLockOrAggregateLookup() {
    Company company = new Company();
    company.setCode("ACME");

    Supplier supplier = new Supplier();
    supplier.setCompany(company);

    JournalEntry journalEntry = new JournalEntry();
    AbstractPartnerLedgerService.LedgerContext context =
        new AbstractPartnerLedgerService.LedgerContext(
            LocalDate.of(2026, 3, 28),
            "SUP-SET-001",
            "supplier settlement",
            new BigDecimal("125.50"),
            BigDecimal.ZERO,
            journalEntry);

    supplierLedgerService.recordLedgerEntry(supplier, context);

    ArgumentCaptor<SupplierLedgerEntry> entryCaptor =
        ArgumentCaptor.forClass(SupplierLedgerEntry.class);
    verify(supplierLedgerRepository).save(entryCaptor.capture());
    SupplierLedgerEntry savedEntry = entryCaptor.getValue();
    assertThat(savedEntry.getCompany()).isSameAs(company);
    assertThat(savedEntry.getSupplier()).isSameAs(supplier);
    assertThat(savedEntry.getEntryDate()).isEqualTo(LocalDate.of(2026, 3, 28));
    assertThat(savedEntry.getReferenceNumber()).isEqualTo("SUP-SET-001");
    assertThat(savedEntry.getMemo()).isEqualTo("supplier settlement");
    assertThat(savedEntry.getJournalEntry()).isSameAs(journalEntry);
    assertThat(savedEntry.getDebit()).isEqualByComparingTo("125.50");
    assertThat(savedEntry.getCredit()).isEqualByComparingTo("0");

    verifyNoInteractions(supplierRepository);
    verify(supplierLedgerRepository, never()).aggregateBalance(any(), eq(supplier));
  }

  @Test
  void recordLedgerEntry_skipsPersistWhenDebitAndCreditAreZero() {
    Supplier supplier = new Supplier();
    AbstractPartnerLedgerService.LedgerContext context =
        new AbstractPartnerLedgerService.LedgerContext(
            LocalDate.of(2026, 3, 28), "SUP-SET-002", "zero-entry", BigDecimal.ZERO, null, null);

    supplierLedgerService.recordLedgerEntry(supplier, context);

    verify(supplierLedgerRepository, never()).save(any(SupplierLedgerEntry.class));
    verify(supplierLedgerRepository, never()).aggregateBalance(any(), any(Supplier.class));
    verifyNoInteractions(supplierRepository);
  }
}
