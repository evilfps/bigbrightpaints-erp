package com.bigbrightpaints.erp.modules.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class ReportServiceAccountStatementTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private AccountingPeriodSnapshotRepository snapshotRepository;
  @Mock private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private DealerLedgerRepository dealerLedgerRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private CompanyClock companyClock;
  @Mock private InventoryValuationQueryService inventoryValuationService;
  @Mock private TrialBalanceReportQueryService trialBalanceReportQueryService;
  @Mock private ProfitLossReportQueryService profitLossReportQueryService;
  @Mock private BalanceSheetReportQueryService balanceSheetReportQueryService;
  @Mock private AgedDebtorsReportQueryService agedDebtorsReportQueryService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  private final GstService gstService = new GstService();
  private ReportService reportService;
  private Company company;

  @BeforeEach
  void setUp() {
    reportService =
        new ReportService(
            companyContextService,
            accountRepository,
            accountingPeriodRepository,
            snapshotRepository,
            snapshotLineRepository,
            dealerRepository,
            dealerLedgerService,
            dealerLedgerRepository,
            journalEntryRepository,
            journalLineRepository,
            productionLogRepository,
            packingRecordRepository,
            inventoryMovementRepository,
            rawMaterialMovementRepository,
            companyEntityLookup,
            companyClock,
            inventoryValuationService,
            trialBalanceReportQueryService,
            profitLossReportQueryService,
            balanceSheetReportQueryService,
            agedDebtorsReportQueryService,
            invoiceRepository,
            rawMaterialPurchaseRepository,
            gstService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 501L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void accountStatement_includesJournalEntryIdFromLatestLedgerEntry() {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 11L);
    dealer.setName("Dealer Trace");
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(11L)))
        .thenReturn(Map.of(11L, new BigDecimal("275.00")));

    DealerLedgerEntry latest = new DealerLedgerEntry();
    latest.setEntryDate(LocalDate.of(2026, 2, 12));
    latest.setReferenceNumber("INV-TRACE-1");
    latest.setDebit(new BigDecimal("300.00"));
    latest.setCredit(new BigDecimal("25.00"));
    JournalEntry journalEntry = new JournalEntry();
    ReflectionTestUtils.setField(journalEntry, "id", 9001L);
    latest.setJournalEntry(journalEntry);
    when(dealerLedgerRepository.findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(
            company, dealer))
        .thenReturn(Optional.of(latest));

    var rows = reportService.accountStatement();

    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.dealerName()).isEqualTo("Dealer Trace");
    assertThat(row.reference()).isEqualTo("INV-TRACE-1");
    assertThat(row.balance()).isEqualByComparingTo("275.00");
    assertThat(row.journalEntryId()).isEqualTo(9001L);
  }

  @Test
  void accountStatement_usesBalanceFallbackWithNullJournalLinkWhenNoLedgerEntry() {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 12L);
    dealer.setName("Dealer No Ledger");
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(12L)))
        .thenReturn(Map.of(12L, new BigDecimal("88.00")));
    when(dealerLedgerRepository.findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(
            company, dealer))
        .thenReturn(Optional.empty());
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 13));

    var rows = reportService.accountStatement();

    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.reference()).isEqualTo("BALANCE");
    assertThat(row.date()).isEqualTo(LocalDate.of(2026, 2, 13));
    assertThat(row.debit()).isEqualByComparingTo("88.00");
    assertThat(row.credit()).isEqualByComparingTo("0.00");
    assertThat(row.balance()).isEqualByComparingTo("88.00");
    assertThat(row.journalEntryId()).isNull();
  }

  @Test
  void accountStatement_handlesLatestLedgerEntryWithoutJournalLink() {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 13L);
    dealer.setName("Dealer Ledger No Journal");
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(13L)))
        .thenReturn(Map.of(13L, new BigDecimal("25.00")));

    DealerLedgerEntry latest = new DealerLedgerEntry();
    latest.setEntryDate(LocalDate.of(2026, 2, 12));
    latest.setReferenceNumber("REC-UNLINKED");
    latest.setDebit(new BigDecimal("55.00"));
    latest.setCredit(new BigDecimal("30.00"));
    latest.setJournalEntry(null);
    when(dealerLedgerRepository.findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(
            company, dealer))
        .thenReturn(Optional.of(latest));

    var rows = reportService.accountStatement();

    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.dealerName()).isEqualTo("Dealer Ledger No Journal");
    assertThat(row.reference()).isEqualTo("REC-UNLINKED");
    assertThat(row.debit()).isEqualByComparingTo("55.00");
    assertThat(row.credit()).isEqualByComparingTo("30.00");
    assertThat(row.balance()).isEqualByComparingTo("25.00");
    assertThat(row.journalEntryId()).isNull();
  }

  @Test
  void accountStatement_rejectsNullBalanceMap() {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 14L);
    dealer.setName("Dealer Null Balances");
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(14L))).thenReturn(null);

    assertThatThrownBy(() -> reportService.accountStatement())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer balance snapshot unavailable");
    verifyNoInteractions(dealerLedgerRepository);
  }
}
