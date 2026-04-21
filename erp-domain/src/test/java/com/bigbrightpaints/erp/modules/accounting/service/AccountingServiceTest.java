package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;

@ExtendWith(MockitoExtension.class)
class AccountingServiceTest {

  @Mock private AccountResolutionOwnerService accountResolutionOwnerService;
  @Mock private JournalEntryService journalEntryService;
  @Mock private DealerReceiptService dealerReceiptService;
  @Mock private SettlementService settlementService;
  @Mock private CreditDebitNoteService creditDebitNoteService;
  @Mock private InventoryAccountingService inventoryAccountingService;

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
            inventoryAccountingService);
  }

  @Test
  void createJournalEntry_delegatesToJournalEntryService() {
    JournalEntryRequest request = journalEntryRequest("JRN-1001");
    JournalEntryDto expected = journalEntryDto(1001L, "JRN-1001");
    when(journalEntryService.createJournalEntry(request)).thenReturn(expected);

    assertThat(accountingService.createJournalEntry(request)).isSameAs(expected);

    verify(journalEntryService).createJournalEntry(request);
  }

  @Test
  void createManualJournal_delegatesToJournalEntryService() {

    ManualJournalRequest request =
        new ManualJournalRequest(
            LocalDate.of(2026, 4, 1),
            "Manual entry",
            "manual-key",
            Boolean.FALSE,
            List.of(
                new ManualJournalRequest.LineRequest(
                    11L, new BigDecimal("10.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                new ManualJournalRequest.LineRequest(
                    12L,
                    new BigDecimal("10.00"),
                    "Credit",
                    ManualJournalRequest.EntryType.CREDIT)));
    JournalEntryDto expected = journalEntryDto(1002L, "MANUAL-1002");
    when(journalEntryService.createManualJournal(request)).thenReturn(expected);

    assertThat(accountingService.createManualJournal(request)).isSameAs(expected);

    verify(journalEntryService).createManualJournal(request);
  }

  @Test
  void dealerReceiptService_routesLiveReceiptFlowThroughJournalEntryService() {
    DealerReceiptRequest request =
        new DealerReceiptRequest(
            21L,
            31L,
            new BigDecimal("125.00"),
            "RCPT-1003",
            "Dealer receipt",
            "receipt-key",
            List.of());
    JournalEntryDto expected = journalEntryDto(1003L, "RCPT-1003");
    when(dealerReceiptService.recordDealerReceipt(request)).thenReturn(expected);

    assertThat(accountingService.recordDealerReceipt(request)).isSameAs(expected);

    verify(dealerReceiptService).recordDealerReceipt(request);
  }

  @Test
  void creditDebitNoteService_routesLiveCreditNoteFlowThroughJournalEntryService() {
    CreditNoteRequest request =
        new CreditNoteRequest(
            41L,
            new BigDecimal("45.00"),
            LocalDate.of(2026, 4, 2),
            "CRN-1004",
            "Credit note",
            "credit-key",
            Boolean.FALSE);
    JournalEntryDto expected = journalEntryDto(1004L, "CRN-1004");
    when(creditDebitNoteService.postCreditNote(request)).thenReturn(expected);

    assertThat(accountingService.postCreditNote(request)).isSameAs(expected);

    verify(creditDebitNoteService).postCreditNote(request);
  }

  @Test
  void inventoryAccountingService_routesLiveLandedCostFlowThroughJournalEntryService() {
    LandedCostRequest request =
        new LandedCostRequest(
            51L,
            new BigDecimal("75.00"),
            61L,
            62L,
            LocalDate.of(2026, 4, 3),
            "Landed cost",
            "LC-1005",
            "landed-key",
            Boolean.FALSE);
    JournalEntryDto expected = journalEntryDto(1005L, "LC-1005");
    when(inventoryAccountingService.recordLandedCost(request)).thenReturn(expected);

    assertThat(accountingService.recordLandedCost(request)).isSameAs(expected);

    verify(inventoryAccountingService).recordLandedCost(request);
  }

  private JournalEntryRequest journalEntryRequest(String referenceNumber) {
    return new JournalEntryRequest(
        referenceNumber,
        LocalDate.of(2026, 4, 1),
        "memo",
        null,
        null,
        Boolean.FALSE,
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                11L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                12L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))));
  }

  private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 4, 1),
        "memo",
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
