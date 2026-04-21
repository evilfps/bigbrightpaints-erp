package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Tag("critical")
class AccountingEndpointContractTest {

  @Test
  void createJournalEntry_delegatesToAccountingServiceWithReferenceAsIdempotencyKey() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalController controller =
        newJournalController(
            accountingService, mock(JournalEntryService.class), null, mock(AccountingFacade.class));
    JournalEntryRequest request =
        new JournalEntryRequest(
            "manual-100",
            LocalDate.of(2026, 2, 28),
            "Manual adjustment",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("50.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Credit", BigDecimal.ZERO, new BigDecimal("50.00"))));
    JournalEntryDto expected =
        new JournalEntryDto(
            100L,
            null,
            "JRN-100",
            LocalDate.of(2026, 2, 28),
            "Manual adjustment",
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
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    when(accountingService.createManualJournalEntry(
            any(JournalEntryRequest.class), eq("manual-100")))
        .thenReturn(expected);

    ApiResponse<JournalEntryDto> body = controller.createJournalEntry(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(expected);
    verify(accountingService).createManualJournalEntry(requestCaptor.capture(), eq("manual-100"));
    assertThat(requestCaptor.getValue().referenceNumber()).isNull();
    assertThat(requestCaptor.getValue().entryDate()).isEqualTo(request.entryDate());
    assertThat(requestCaptor.getValue().memo()).isEqualTo(request.memo());
    assertThat(requestCaptor.getValue().lines()).isEqualTo(request.lines());
  }

  @Test
  void createJournalEntry_rejectsReservedReferenceNamespace() {
    JournalController controller =
        newJournalController(
            mock(AccountingService.class), mock(JournalEntryService.class), null, null);
    JournalEntryRequest request =
        new JournalEntryRequest(
            "INV-2026-0001",
            LocalDate.of(2026, 2, 28),
            "Manual adjustment",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("50.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Credit", BigDecimal.ZERO, new BigDecimal("50.00"))));

    assertThatThrownBy(() -> controller.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage())
                  .contains("Reference number is reserved for system journals");
            });
  }

  @Test
  void createJournalEntry_allowsNullSupplierForApAdjacentManualPosting() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalController controller =
        newJournalController(
            accountingService, mock(JournalEntryService.class), null, mock(AccountingFacade.class));
    JournalEntryRequest request =
        new JournalEntryRequest(
            "client-ap-accrual-001",
            LocalDate.of(2026, 4, 1),
            "AP accrual adjustment",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    101L, "Expense accrual", new BigDecimal("500.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    202L, "AP accrual", BigDecimal.ZERO, new BigDecimal("500.00"))));
    JournalEntryDto expected = expectedJournal(612L, "JRN-612");
    when(accountingService.createManualJournalEntry(
            any(JournalEntryRequest.class), eq("client-ap-accrual-001")))
        .thenReturn(expected);

    ApiResponse<JournalEntryDto> body = controller.createJournalEntry(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(expected);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(accountingService)
        .createManualJournalEntry(requestCaptor.capture(), eq("client-ap-accrual-001"));
    JournalEntryRequest sanitized = requestCaptor.getValue();
    assertThat(sanitized.supplierId()).isNull();
    assertThat(sanitized.dealerId()).isNull();
    assertThat(sanitized.lines()).hasSize(2);
  }

  @Test
  void listJournals_appliesFilterArguments() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalController controller =
        newJournalController(accountingService, mock(JournalEntryService.class), null, null);
    List<JournalListItemDto> expected =
        List.of(
            new JournalListItemDto(
                10L,
                "INV-10",
                LocalDate.of(2026, 2, 27),
                "Dispatch",
                "POSTED",
                "AUTOMATED",
                "SALES",
                "INV-10",
                new BigDecimal("100.00"),
                new BigDecimal("100.00")));
    PageResponse<JournalListItemDto> expectedPage = PageResponse.of(expected, 1, 2, 40);
    when(accountingService.listJournals(
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "AUTOMATED", "SALES", 2, 40))
        .thenReturn(expectedPage);

    ApiResponse<PageResponse<JournalListItemDto>> body =
        controller
            .listJournals(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "AUTOMATED", "SALES", 2, 40)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expectedPage);
  }

  @Test
  void parseOptionalDate_returnsNullForBlankInput() {
    assertThat(AccountingDateParameters.parseOptionalDate("   ", "fromDate")).isNull();
  }

  @Test
  void parseRequiredDate_trimsValidIsoDate() {
    LocalDate parsed = AccountingDateParameters.parseRequiredDate(" 2026-03-10 ", "fromDate");
    assertThat(parsed).isEqualTo(LocalDate.of(2026, 3, 10));
  }

  @Test
  void parseRequiredDate_rejectsInvalidIsoDateWithFieldDetail() {
    assertThatThrownBy(() -> AccountingDateParameters.parseRequiredDate("03/10/2026", "date"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_DATE);
              assertThat(applicationException.getDetails()).containsEntry("date", "03/10/2026");
            });
  }

  @Test
  void supplierStatement_parsesOptionalDates() {
    StatementService statementService = mock(StatementService.class);
    PartnerStatementResponse expected =
        new PartnerStatementResponse(
            7L,
            "Supplier 7",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            BigDecimal.ZERO,
            new BigDecimal("42.00"),
            List.of());
    when(statementService.supplierStatement(
            7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<PartnerStatementResponse> body =
        controllerWithStatementService(statementService)
            .supplierStatement(7L, " 2026-03-01 ", "2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(statementService)
        .supplierStatement(7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void supplierAging_parsesOptionalAsOfDate() {
    StatementService statementService = mock(StatementService.class);
    AgingSummaryResponse expected =
        new AgingSummaryResponse(7L, "Supplier 7", new BigDecimal("42.00"), List.of());
    when(statementService.supplierAging(7L, LocalDate.of(2026, 3, 31), "30,60,90"))
        .thenReturn(expected);

    ApiResponse<AgingSummaryResponse> body =
        controllerWithStatementService(statementService)
            .supplierAging(7L, " 2026-03-31 ", "30,60,90")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(statementService).supplierAging(7L, LocalDate.of(2026, 3, 31), "30,60,90");
  }

  @Test
  void supplierStatementPdf_parsesOptionalDates() {
    StatementService statementService = mock(StatementService.class);
    byte[] pdf = new byte[] {1, 2, 3};
    when(statementService.supplierStatementPdf(
            7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(pdf);

    byte[] body =
        controllerWithStatementService(statementService)
            .supplierStatementPdf(7L, " 2026-03-01 ", "2026-03-31 ")
            .getBody();

    assertThat(body).isEqualTo(pdf);
    verify(statementService)
        .supplierStatementPdf(7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void supplierAgingPdf_parsesOptionalAsOfDate() {
    StatementService statementService = mock(StatementService.class);
    byte[] pdf = new byte[] {4, 5, 6};
    when(statementService.supplierAgingPdf(7L, LocalDate.of(2026, 3, 31), "30,60,90"))
        .thenReturn(pdf);

    byte[] body =
        controllerWithStatementService(statementService)
            .supplierAgingPdf(7L, " 2026-03-31 ", "30,60,90")
            .getBody();

    assertThat(body).isEqualTo(pdf);
    verify(statementService).supplierAgingPdf(7L, LocalDate.of(2026, 3, 31), "30,60,90");
  }

  @Test
  void getBalanceAsOf_parsesRequiredDate() {
    AccountingService accountingService = mock(AccountingService.class);
    when(accountingService.getBalanceAsOf(9L, LocalDate.of(2026, 3, 31)))
        .thenReturn(new BigDecimal("42.00"));

    ApiResponse<BigDecimal> body =
        controllerWithAccountingService(accountingService)
            .getBalanceAsOf(9L, " 2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualByComparingTo("42.00");
    verify(accountingService).getBalanceAsOf(9L, LocalDate.of(2026, 3, 31));
  }

  @Test
  void getTrialBalanceAsOf_parsesRequiredDate() {
    AccountingService accountingService = mock(AccountingService.class);
    TemporalBalanceService.TrialBalanceSnapshot expected =
        new TemporalBalanceService.TrialBalanceSnapshot(
            LocalDate.of(2026, 3, 31), List.of(), BigDecimal.ONE, BigDecimal.ONE);
    when(accountingService.getTrialBalanceAsOf(LocalDate.of(2026, 3, 31))).thenReturn(expected);

    ApiResponse<TemporalBalanceService.TrialBalanceSnapshot> body =
        controllerWithAccountingService(accountingService)
            .getTrialBalanceAsOf(" 2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(accountingService).getTrialBalanceAsOf(LocalDate.of(2026, 3, 31));
  }

  @Test
  void getAccountActivity_wrapsInvalidDateFormat() {
    StatementReportController controller =
        controllerWithAccountingService(mock(AccountingService.class));

    assertThatThrownBy(() -> controller.getAccountActivity(9L, "bad", "2026-03-31", null, null))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_DATE);
              assertThat(applicationException.getDetails())
                  .containsEntry("startDate", "bad")
                  .containsEntry("endDate", "2026-03-31");
            });
  }

  @Test
  void getAccountActivity_parsesRequiredDates() {
    AccountingService accountingService = mock(AccountingService.class);
    TemporalBalanceService.AccountActivityReport expected =
        new TemporalBalanceService.AccountActivityReport(
            "AR-009",
            "Accounts Receivable",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            new BigDecimal("10.00"),
            new BigDecimal("12.00"),
            new BigDecimal("20.00"),
            new BigDecimal("18.00"),
            new BigDecimal("2.00"),
            List.of());
    when(accountingService.getAccountActivity(
            9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<StatementReportControllerSupport.AccountActivitySummaryResponse> body =
        controllerWithAccountingService(accountingService)
            .getAccountActivity(9L, " 2026-03-01 ", "2026-03-31 ", null, null)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data().totalDebits()).isEqualByComparingTo("20.00");
    assertThat(body.data().totalCredits()).isEqualByComparingTo("18.00");
    assertThat(body.data().netMovement()).isEqualByComparingTo("2.00");
    assertThat(body.data().transactionCount()).isEqualTo(0);
    verify(accountingService)
        .getAccountActivity(9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void compareBalances_parsesRequiredDates() {
    AccountingService accountingService = mock(AccountingService.class);
    TemporalBalanceService.BalanceComparison expected =
        new TemporalBalanceService.BalanceComparison(
            9L,
            LocalDate.of(2026, 3, 1),
            new BigDecimal("10.00"),
            LocalDate.of(2026, 3, 31),
            new BigDecimal("12.00"),
            new BigDecimal("2.00"));
    when(accountingService.compareBalances(9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<StatementReportControllerSupport.BalanceComparisonResponse> body =
        controllerWithAccountingService(accountingService)
            .compareBalances(9L, " 2026-03-01 ", "2026-03-31 ", null, null)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data().fromBalance()).isEqualByComparingTo("10.00");
    assertThat(body.data().toBalance()).isEqualByComparingTo("12.00");
    assertThat(body.data().change()).isEqualByComparingTo("2.00");
    verify(accountingService)
        .compareBalances(9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void reverseJournalEntry_delegatesToJournalEntryService() {
    JournalEntryService journalEntryService = mock(JournalEntryService.class);
    JournalController controller =
        newJournalController(mock(AccountingService.class), journalEntryService, null, null);
    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            LocalDate.of(2026, 2, 28), false, "Correction", "Reversal", false);
    JournalEntryDto expected =
        new JournalEntryDto(
            200L,
            null,
            "REV-200",
            LocalDate.of(2026, 2, 28),
            "Reversal",
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
    when(journalEntryService.reverseJournalEntry(200L, request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body = controller.reverseJournalEntry(200L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void listSalesReturns_usesSalesReturnReferencePrefix() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller =
        newStatementController(accountingService, mock(SalesReturnService.class));

    controller.listSalesReturns();

    verify(accountingService).listJournalEntriesByReferencePrefix("CRN-");
  }

  @Test
  void listSalesReturns_filtersOutCogsAndOrphanedCreditNotes() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller =
        newStatementController(accountingService, mock(SalesReturnService.class));
    JournalEntryDto legacySalesReturn = journalEntry(301L, "CRN-100", 11L, null, "Sales return");
    JournalEntryDto correctionLinkedSalesReturn =
        journalEntry(302L, "CRN-101", null, "SALES_RETURN", "Correction-linked sales return");
    JournalEntryDto cogsEntry = journalEntry(303L, "CRN-100-COGS-0", null, null, "COGS reversal");
    JournalEntryDto orphanedCreditNote =
        journalEntry(304L, "CRN-ORPHAN-1", null, null, "Orphaned credit note");
    when(accountingService.listJournalEntriesByReferencePrefix("CRN-"))
        .thenReturn(
            List.of(legacySalesReturn, correctionLinkedSalesReturn, cogsEntry, orphanedCreditNote));

    ApiResponse<List<JournalEntryDto>> body = controller.listSalesReturns().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).containsExactly(legacySalesReturn, correctionLinkedSalesReturn);
  }

  @Test
  void listSalesReturns_filtersNullBlankAndNonCrnEntries() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller =
        newStatementController(accountingService, mock(SalesReturnService.class));
    JournalEntryDto blankReference = journalEntry(305L, "   ", 12L, null, "Blank reference");
    JournalEntryDto nonCrnReference =
        journalEntry(306L, "DN-200", 12L, "SALES_RETURN", "Wrong prefix");
    JournalEntryDto validSalesReturn =
        journalEntry(307L, "CRN-200", 12L, null, "Valid sales return");

    when(accountingService.listJournalEntriesByReferencePrefix("CRN-"))
        .thenReturn(
            java.util.Arrays.asList(null, blankReference, nonCrnReference, validSalesReturn));

    ApiResponse<List<JournalEntryDto>> body = controller.listSalesReturns().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).containsExactly(validSalesReturn);
  }

  @Test
  void previewSalesReturn_delegatesToSalesReturnService() {
    SalesReturnService salesReturnService = mock(SalesReturnService.class);
    StatementReportController controller =
        newStatementController(mock(AccountingService.class), salesReturnService);
    SalesReturnRequest request =
        new SalesReturnRequest(
            10L,
            "Damaged",
            List.of(new SalesReturnRequest.ReturnLine(20L, new BigDecimal("1.00"))));
    SalesReturnPreviewDto preview =
        new SalesReturnPreviewDto(
            10L, "INV-10", new BigDecimal("100.00"), new BigDecimal("50.00"), List.of());
    when(salesReturnService.previewReturn(request)).thenReturn(preview);

    ApiResponse<SalesReturnPreviewDto> body = controller.previewSalesReturn(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(preview);
  }

  @Test
  void createJournalEntry_delegatesToAccountingServiceWithSanitizedManualPayload() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalController controller =
        newJournalController(
            accountingService, mock(JournalEntryService.class), null, mock(AccountingFacade.class));
    JournalEntryRequest request =
        new JournalEntryRequest(
            "BRIDGE-ENTRY-1",
            LocalDate.of(2026, 2, 28),
            "Bridge entry",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("50.00"), BigDecimal.ZERO)));
    JournalEntryDto expected =
        new JournalEntryDto(
            501L,
            null,
            "BRIDGE-ENTRY-1",
            LocalDate.of(2026, 2, 28),
            "Bridge entry",
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
    when(accountingService.createManualJournalEntry(
            any(JournalEntryRequest.class), eq("BRIDGE-ENTRY-1")))
        .thenReturn(expected);

    ApiResponse<JournalEntryDto> body = controller.createJournalEntry(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(accountingService)
        .createManualJournalEntry(requestCaptor.capture(), eq("BRIDGE-ENTRY-1"));
    JournalEntryRequest sanitized = requestCaptor.getValue();
    assertThat(sanitized.referenceNumber()).isNull();
    assertThat(sanitized.entryDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(sanitized.memo()).isEqualTo("Bridge entry");
    assertThat(sanitized.lines()).hasSize(1);
    assertThat(sanitized.attachmentReferences()).isEmpty();
  }

  @Test
  void createJournalEntry_keepsAttachmentReferencesWhileSanitizingManualFields() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalController controller =
        newJournalController(
            accountingService, mock(JournalEntryService.class), null, mock(AccountingFacade.class));
    JournalEntryRequest request =
        new JournalEntryRequest(
            "MANUAL-2026-0001",
            LocalDate.of(2026, 3, 1),
            "Manual close-period note",
            11L,
            22L,
            Boolean.TRUE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("90.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Credit", BigDecimal.ZERO, new BigDecimal("90.00"))),
            "INR",
            new BigDecimal("1.00"),
            "UPSTREAM",
            "SRC-1",
            "AUTOMATED",
            List.of("scan-1", "scan-2"));
    when(accountingService.createManualJournalEntry(
            any(JournalEntryRequest.class), eq("MANUAL-2026-0001")))
        .thenReturn(expectedJournal(611L, "MANUAL-2026-0001"));

    controller.createJournalEntry(request);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(accountingService)
        .createManualJournalEntry(requestCaptor.capture(), eq("MANUAL-2026-0001"));
    JournalEntryRequest sanitized = requestCaptor.getValue();
    assertThat(sanitized.referenceNumber()).isNull();
    assertThat(sanitized.sourceModule()).isNull();
    assertThat(sanitized.sourceReference()).isNull();
    assertThat(sanitized.journalType()).isNull();
    assertThat(sanitized.attachmentReferences()).containsExactly("scan-1", "scan-2");
  }

  @Test
  void journalEntries_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    List<JournalEntryDto> expected = List.of(expectedJournal(81L, "JRN-81"));
    when(accountingService.listJournalEntries(1L, 2L, 3, 4, null)).thenReturn(expected);

    ApiResponse<List<JournalEntryDto>> body =
        newJournalController(accountingService, mock(JournalEntryService.class), null, null)
            .journalEntries(1L, 2L, null, 3, 4)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void journalEntries_withSourceFilter_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    List<JournalEntryDto> expected = List.of(expectedJournal(82L, "PACK-1"));
    when(accountingService.listJournalEntries(null, null, 0, 100, "PACKING")).thenReturn(expected);

    ApiResponse<List<JournalEntryDto>> body =
        newJournalController(accountingService, mock(JournalEntryService.class), null, null)
            .journalEntries(null, null, "PACKING", 0, 100)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void journalEntries_withDealerAndSourceFilter_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    List<JournalEntryDto> expected = List.of(expectedJournal(83L, "PACK-D-1"));
    when(accountingService.listJournalEntries(19L, null, 2, 25, "PACKING")).thenReturn(expected);

    ApiResponse<List<JournalEntryDto>> body =
        newJournalController(accountingService, mock(JournalEntryService.class), null, null)
            .journalEntries(19L, null, "PACKING", 2, 25)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void recordPayrollPayment_delegatesToAccountingFacade() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    PayrollPaymentRequest request =
        new PayrollPaymentRequest(7L, 11L, 12L, new BigDecimal("25.00"), "PAY-7", "memo");
    JournalEntryDto expected = expectedJournal(82L, "PAY-7");
    when(accountingFacade.recordPayrollPayment(request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body =
        newJournalController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                null,
                accountingFacade)
            .recordPayrollPayment(request)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void postCreditNote_delegatesToCreditDebitNoteService() {
    CreditDebitNoteService creditDebitNoteService = mock(CreditDebitNoteService.class);
    CreditNoteRequest request =
        new CreditNoteRequest(
            4L, new BigDecimal("10.00"), LocalDate.of(2026, 3, 2), "CRN-1", "memo", null, false);
    JournalEntryDto expected = expectedJournal(83L, "CRN-1");
    when(creditDebitNoteService.postCreditNote(request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body =
        newJournalController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                creditDebitNoteService,
                null)
            .postCreditNote(request)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void postDebitNote_delegatesToCreditDebitNoteService() {
    CreditDebitNoteService creditDebitNoteService = mock(CreditDebitNoteService.class);
    DebitNoteRequest request =
        new DebitNoteRequest(
            5L, new BigDecimal("12.00"), LocalDate.of(2026, 3, 3), "DBN-1", "memo", null, false);
    JournalEntryDto expected = expectedJournal(84L, "DBN-1");
    when(creditDebitNoteService.postDebitNote(request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body =
        newJournalController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                creditDebitNoteService,
                null)
            .postDebitNote(request)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void postAccrual_delegatesToCreditDebitNoteService() {
    CreditDebitNoteService creditDebitNoteService = mock(CreditDebitNoteService.class);
    AccrualRequest request =
        new AccrualRequest(
            5L,
            6L,
            new BigDecimal("13.00"),
            LocalDate.of(2026, 3, 4),
            "ACR-1",
            "memo",
            null,
            LocalDate.of(2026, 4, 4),
            false);
    JournalEntryDto expected = expectedJournal(85L, "ACR-1");
    when(creditDebitNoteService.postAccrual(request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body =
        newJournalController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                creditDebitNoteService,
                null)
            .postAccrual(request)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void writeOffBadDebt_delegatesToCreditDebitNoteService() {
    CreditDebitNoteService creditDebitNoteService = mock(CreditDebitNoteService.class);
    BadDebtWriteOffRequest request =
        new BadDebtWriteOffRequest(
            5L, 6L, new BigDecimal("14.00"), LocalDate.of(2026, 3, 5), "BD-1", "memo", null, false);
    JournalEntryDto expected = expectedJournal(86L, "BD-1");
    when(creditDebitNoteService.writeOffBadDebt(request)).thenReturn(expected);

    ApiResponse<JournalEntryDto> body =
        newJournalController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                creditDebitNoteService,
                null)
            .writeOffBadDebt(request)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void settleDealer_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = controllerWithSettlementFacade(accountingFacade);
    PartnerSettlementRequest request =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            9L,
            20L,
            21L,
            22L,
            23L,
            24L,
            new BigDecimal("180.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION,
            LocalDate.of(2026, 3, 2),
            "HDR-DEALER-1",
            "dealer settlement",
            null,
            Boolean.TRUE,
            null);
    PartnerSettlementResponse expected =
        new PartnerSettlementResponse(
            expectedJournal(701L, "HDR-DEALER-1"),
            new BigDecimal("180.00"),
            new BigDecimal("180.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    when(accountingFacade.settleDealerInvoices(any(PartnerSettlementRequest.class)))
        .thenReturn(expected);

    controller.settleDealer(request, "IDEMP-DEALER-HDR-1", null);

    ArgumentCaptor<PartnerSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(PartnerSettlementRequest.class);
    verify(accountingFacade).settleDealerInvoices(requestCaptor.capture());
    PartnerSettlementRequest resolved = requestCaptor.getValue();
    assertThat(resolved.amount()).isEqualByComparingTo("180.00");
    assertThat(resolved.unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-DEALER-HDR-1");
  }

  @Test
  void settleSupplier_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = controllerWithSettlementFacade(accountingFacade);
    PartnerSettlementRequest request =
        new PartnerSettlementRequest(
            PartnerType.SUPPLIER,
            8L,
            20L,
            21L,
            22L,
            23L,
            24L,
            new BigDecimal("95.00"),
            SettlementAllocationApplication.ON_ACCOUNT,
            LocalDate.of(2026, 3, 3),
            "HDR-SUP-1",
            "supplier settlement",
            null,
            Boolean.TRUE,
            null);
    PartnerSettlementResponse expected =
        new PartnerSettlementResponse(
            expectedJournal(702L, "HDR-SUP-1"),
            new BigDecimal("95.00"),
            new BigDecimal("95.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    when(accountingFacade.settleSupplierInvoices(any(PartnerSettlementRequest.class)))
        .thenReturn(expected);

    controller.settleSupplier(request, "IDEMP-SUP-HDR-1", null);

    ArgumentCaptor<PartnerSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(PartnerSettlementRequest.class);
    verify(accountingFacade).settleSupplierInvoices(requestCaptor.capture());
    PartnerSettlementRequest resolved = requestCaptor.getValue();
    assertThat(resolved.amount()).isEqualByComparingTo("95.00");
    assertThat(resolved.unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-SUP-HDR-1");
  }

  @Test
  void autoSettleSupplier_appliesHeaderOnlyIdempotencyKey() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = controllerWithSettlementFacade(accountingFacade);
    AutoSettlementRequest request =
        new AutoSettlementRequest(
            42L, new BigDecimal("75.00"), "AUTO-SUP-1", "auto supplier settlement", null);
    PartnerSettlementResponse expected =
        new PartnerSettlementResponse(
            expectedJournal(703L, "AUTO-SUP-1"),
            new BigDecimal("75.00"),
            new BigDecimal("75.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    when(accountingFacade.autoSettleSupplier(eq(8L), any(AutoSettlementRequest.class)))
        .thenReturn(expected);

    controller.autoSettleSupplier(8L, request, "IDEMP-SUP-AUTO-1", null);

    ArgumentCaptor<AutoSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(AutoSettlementRequest.class);
    verify(accountingFacade).autoSettleSupplier(eq(8L), requestCaptor.capture());
    AutoSettlementRequest resolved = requestCaptor.getValue();
    assertThat(resolved.amount()).isEqualByComparingTo("75.00");
    assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-SUP-AUTO-1");
  }

  @Test
  void settlementAllocationApplication_isUnappliedCoversDocumentAndUnappliedModes() {
    assertThat(SettlementAllocationApplication.DOCUMENT.isUnapplied()).isFalse();
    assertThat(SettlementAllocationApplication.ON_ACCOUNT.isUnapplied()).isTrue();
    assertThat(SettlementAllocationApplication.FUTURE_APPLICATION.isUnapplied()).isTrue();
  }

  @Test
  void accountingFacade_createManualJournal_requiresReason() {
    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            mock(AccountingService.class),
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(
                com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService
                    .class),
            mock(
                com.bigbrightpaints.erp.modules.accounting.service
                    .CompanyScopedAccountingLookupService.class),
            mock(CompanyAccountingSettingsService.class),
            mock(JournalReferenceResolver.class),
            mock(JournalReferenceMappingRepository.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                facade.createManualJournal(
                    new ManualJournalRequest(
                        LocalDate.of(2026, 3, 1),
                        "   ",
                        "MANUAL-EMPTY",
                        Boolean.FALSE,
                        List.of(
                            new ManualJournalRequest.LineRequest(
                                11L,
                                new BigDecimal("10.00"),
                                "Debit",
                                ManualJournalRequest.EntryType.DEBIT),
                            new ManualJournalRequest.LineRequest(
                                22L,
                                new BigDecimal("10.00"),
                                "Credit",
                                ManualJournalRequest.EntryType.CREDIT)),
                        List.of("scan-9"))))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Manual journal reason is required");
  }

  @Test
  void accountingFacade_createManualJournalEntry_forwardsAttachmentReferences() {
    AccountingService accountingService = mock(AccountingService.class);
    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            accountingService,
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(
                com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService
                    .class),
            mock(
                com.bigbrightpaints.erp.modules.accounting.service
                    .CompanyScopedAccountingLookupService.class),
            mock(CompanyAccountingSettingsService.class),
            mock(JournalReferenceResolver.class),
            mock(JournalReferenceMappingRepository.class));
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    when(accountingService.createManualJournalEntry(requestCaptor.capture(), eq("IDEMP-FACADE-1")))
        .thenReturn(expectedJournal(703L, "MANUAL-FACADE-1"));

    facade.createManualJournalEntry(
        new JournalEntryRequest(
            "MANUAL-FACADE-1",
            LocalDate.of(2026, 3, 4),
            "manual facade journal",
            null,
            null,
            Boolean.TRUE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("55.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Credit", BigDecimal.ZERO, new BigDecimal("55.00"))),
            null,
            null,
            null,
            null,
            null,
            List.of("scan-a", "scan-b")),
        "IDEMP-FACADE-1");

    assertThat(requestCaptor.getValue().attachmentReferences()).containsExactly("scan-a", "scan-b");
    assertThat(requestCaptor.getValue().memo()).isEqualTo("manual facade journal");
    assertThat(requestCaptor.getValue().sourceModule()).isNull();
  }

  @Test
  void accountingFacade_createManualJournal_forwardsAttachmentReferencesAndIdempotencyKey() {
    AccountingService accountingService = mock(AccountingService.class);
    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            accountingService,
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(
                com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService
                    .class),
            mock(
                com.bigbrightpaints.erp.modules.accounting.service
                    .CompanyScopedAccountingLookupService.class),
            mock(CompanyAccountingSettingsService.class),
            mock(JournalReferenceResolver.class),
            mock(JournalReferenceMappingRepository.class));
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    when(accountingService.createStandardJournal(requestCaptor.capture()))
        .thenReturn(expectedJournal(704L, "MANUAL-FACADE-2"));

    facade.createManualJournal(
        new ManualJournalRequest(
            LocalDate.of(2026, 3, 5),
            "manual correction reason",
            "IDEMP-FACADE-2",
            Boolean.TRUE,
            List.of(
                new ManualJournalRequest.LineRequest(
                    11L, new BigDecimal("25.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                new ManualJournalRequest.LineRequest(
                    22L, new BigDecimal("25.00"), "Credit", ManualJournalRequest.EntryType.CREDIT)),
            List.of("manual-scan-1", "manual-scan-2")));

    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo("IDEMP-FACADE-2");
    assertThat(requestCaptor.getValue().attachmentReferences())
        .containsExactly("manual-scan-1", "manual-scan-2");
    assertThat(requestCaptor.getValue().narration()).isEqualTo("manual correction reason");
  }

  @Test
  void accountingFacade_createManualJournalEntry_requiresReason() {
    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            mock(AccountingService.class),
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(
                com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService
                    .class),
            mock(
                com.bigbrightpaints.erp.modules.accounting.service
                    .CompanyScopedAccountingLookupService.class),
            mock(CompanyAccountingSettingsService.class),
            mock(JournalReferenceResolver.class),
            mock(JournalReferenceMappingRepository.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                facade.createManualJournalEntry(
                    new JournalEntryRequest(
                        "MANUAL-FACADE-MISSING-REASON",
                        LocalDate.of(2026, 3, 6),
                        "   ",
                        null,
                        null,
                        Boolean.TRUE,
                        List.of(
                            new JournalEntryRequest.JournalLineRequest(
                                11L, "Debit", new BigDecimal("30.00"), BigDecimal.ZERO),
                            new JournalEntryRequest.JournalLineRequest(
                                22L, "Credit", BigDecimal.ZERO, new BigDecimal("30.00"))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("scan-z")),
                    "IDEMP-FACADE-MISSING-REASON"))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Manual journal reason is required");
  }

  private JournalController newJournalController(
      AccountingService accountingService,
      JournalEntryService journalEntryService,
      CreditDebitNoteService creditDebitNoteService,
      AccountingFacade accountingFacade) {
    return new JournalController(
        accountingService != null ? accountingService : mock(AccountingService.class),
        journalEntryService != null ? journalEntryService : mock(JournalEntryService.class),
        creditDebitNoteService != null
            ? creditDebitNoteService
            : mock(CreditDebitNoteService.class),
        accountingFacade != null ? accountingFacade : mock(AccountingFacade.class));
  }

  private SettlementController controllerWithSettlementFacade(AccountingFacade accountingFacade) {
    return new SettlementController(accountingFacade);
  }

  private StatementReportController newStatementController(
      AccountingService accountingService, SalesReturnService salesReturnService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            accountingService != null ? accountingService : mock(AccountingService.class),
            salesReturnService,
            mock(StatementService.class),
            mock(AuditService.class)));
  }

  private StatementReportController controllerWithStatementService(
      StatementService statementService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            mock(AccountingService.class),
            mock(SalesReturnService.class),
            statementService,
            mock(AuditService.class)));
  }

  private StatementReportController controllerWithAccountingService(
      AccountingService accountingService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            accountingService != null ? accountingService : mock(AccountingService.class),
            mock(SalesReturnService.class),
            mock(StatementService.class),
            mock(AuditService.class)));
  }

  private JournalEntryDto expectedJournal(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 2, 28),
        "Settlement",
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

  private JournalEntryDto journalEntry(
      Long id, String referenceNumber, Long dealerId, String correctionReason, String memo) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 3, 1),
        memo,
        "POSTED",
        dealerId,
        dealerId != null ? "Dealer " + dealerId : null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        correctionReason,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
