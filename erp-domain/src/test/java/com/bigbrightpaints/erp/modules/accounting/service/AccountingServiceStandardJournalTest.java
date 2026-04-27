package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class AccountingServiceStandardJournalTest {

  @Mock private AccountResolutionOwnerService accountResolutionOwnerService;
  @Mock private JournalEntryService journalEntryService;
  @Mock private DealerReceiptService dealerReceiptService;
  @Mock private SettlementService settlementService;
  @Mock private CreditDebitNoteService creditDebitNoteService;
  @Mock private InventoryAccountingService inventoryAccountingService;
  @Mock private TaxService taxService;
  @Mock private TemporalBalanceService temporalBalanceService;
  @Mock private ConfigurationHealthService configurationHealthService;
  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyClock companyClock;

  private AccountingService accountingService;

  @BeforeEach
  void setUp() {
    accountingService =
        new AccountingService(
            accountResolutionOwnerService,
            journalEntryService,
            dealerReceiptService,
            settlementService,
            creditDebitNoteService,
            inventoryAccountingService,
            taxService,
            temporalBalanceService,
            configurationHealthService,
            companyContextService,
            companyClock);
  }

  @Test
  void createManualJournal_balancedMultiLineDelegatesToJournalEntryService() {
    ManualJournalRequest request =
        new ManualJournalRequest(
            LocalDate.of(2026, 2, 28),
            "Manual correction",
            "manual-xyz",
            false,
            List.of(
                new ManualJournalRequest.LineRequest(
                    11L,
                    new BigDecimal("100.00"),
                    "Debit line",
                    ManualJournalRequest.EntryType.DEBIT),
                new ManualJournalRequest.LineRequest(
                    12L,
                    new BigDecimal("40.00"),
                    "Debit line 2",
                    ManualJournalRequest.EntryType.DEBIT),
                new ManualJournalRequest.LineRequest(
                    22L,
                    new BigDecimal("140.00"),
                    "Credit line",
                    ManualJournalRequest.EntryType.CREDIT)));
    JournalEntryDto expected = journalEntryDto(301L, "JRN-301");
    when(journalEntryService.createManualJournal(request)).thenReturn(expected);

    assertThat(accountingService.createManualJournal(request)).isSameAs(expected);
  }

  @Test
  void createManualJournalEntry_delegatesToJournalEntryService() {
    JournalEntryRequest request =
        new JournalEntryRequest(
            null,
            LocalDate.of(2026, 2, 28),
            "Manual correction",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))),
            null,
            null,
            null,
            null,
            null);
    JournalEntryDto expected = journalEntryDto(302L, "JRN-302");
    when(journalEntryService.createManualJournalEntry(request, "manual-key")).thenReturn(expected);

    assertThat(accountingService.createManualJournalEntry(request, "manual-key"))
        .isSameAs(expected);
  }

  private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 2, 28),
        "Manual correction",
        "POSTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.<JournalLineDto>of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
