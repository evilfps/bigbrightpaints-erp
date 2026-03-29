package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.AuditDigestResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Tag("critical")
class AccountingControllerJournalEndpointsTest {

  @Test
  void createJournalEntry_delegatesToAccountingServiceWithReferenceAsIdempotencyKey() {
    AccountingService accountingService = mock(AccountingService.class);
    AccountingController controller =
        newController(accountingService, mock(JournalEntryService.class), null);
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
    AccountingController controller =
        newController(mock(AccountingService.class), mock(JournalEntryService.class), null);
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
  void listJournals_appliesFilterArguments() {
    AccountingService accountingService = mock(AccountingService.class);
    AccountingController controller =
        newController(accountingService, mock(JournalEntryService.class), null);
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
  void transactionAudit_parsesDateFiltersAndPagination() {
    AccountingAuditTrailService accountingAuditTrailService = mock(AccountingAuditTrailService.class);
    AccountingController controller = controllerWithAuditTrailService(accountingAuditTrailService);
    PageResponse<AccountingTransactionAuditListItemDto> expectedPage =
        PageResponse.of(
            List.of(
                new AccountingTransactionAuditListItemDto(
                    88L,
                    "SET-88",
                    LocalDate.of(2026, 3, 10),
                    "POSTED",
                    "SETTLEMENT",
                    "SETTLEMENT_DEALER",
                    "Dealer settlement",
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00"),
                    null,
                    null,
                    null,
                    "OK",
                    Instant.parse("2026-03-10T09:15:00Z"))),
            1,
            1,
            25);
    when(accountingAuditTrailService.listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "SETTLEMENT",
            "POSTED",
            "SET-",
            1,
            25))
        .thenReturn(expectedPage);

    ApiResponse<PageResponse<AccountingTransactionAuditListItemDto>> body =
        controller
            .transactionAudit(
                " 2026-03-01 ", "2026-03-31 ", "SETTLEMENT", "POSTED", "SET-", 1, 25)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expectedPage);
    verify(accountingAuditTrailService)
        .listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "SETTLEMENT",
            "POSTED",
            "SET-",
            1,
            25);
  }

  @Test
  void parseOptionalDate_returnsNullForBlankInput() {
    AccountingController controller =
        newController(mock(AccountingService.class), mock(JournalEntryService.class), null);

    LocalDate parsed =
        (LocalDate)
            ReflectionTestUtils.invokeMethod(controller, "parseOptionalDate", "   ", "fromDate");

    assertThat(parsed)
        .isNull();
  }

  @Test
  void parseRequiredDate_trimsValidIsoDate() {
    AccountingController controller =
        newController(mock(AccountingService.class), mock(JournalEntryService.class), null);

    LocalDate parsed =
        (LocalDate)
            ReflectionTestUtils.invokeMethod(
                controller, "parseRequiredDate", " 2026-03-10 ", "fromDate");

    assertThat(parsed)
        .isEqualTo(LocalDate.of(2026, 3, 10));
  }

  @Test
  void parseRequiredDate_rejectsInvalidIsoDateWithFieldDetail() {
    AccountingController controller =
        newController(mock(AccountingService.class), mock(JournalEntryService.class), null);

    assertThatThrownBy(
            () -> ReflectionTestUtils.invokeMethod(controller, "parseRequiredDate", "03/10/2026", "date"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_DATE);
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
    verify(statementService).supplierStatement(7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
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
    verify(statementService).supplierStatementPdf(7L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
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
  void auditDigest_parsesOptionalDates() {
    AuditDigestResponse expected = new AuditDigestResponse("Mar 2026", List.of("entry-1"));
    AccountingAuditService auditService = mock(AccountingAuditService.class);
    when(auditService.auditDigest(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<AuditDigestResponse> body =
        controllerWithAuditDigestService(auditService).auditDigest(" 2026-03-01 ", "2026-03-31 ").getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(auditService).auditDigest(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void auditDigestCsv_parsesOptionalDates() {
    AccountingAuditService auditService = mock(AccountingAuditService.class);
    when(auditService.auditDigestCsv(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn("col\nvalue");

    String body =
        controllerWithAuditDigestService(auditService)
            .auditDigestCsv(" 2026-03-01 ", "2026-03-31 ")
            .getBody();

    assertThat(body).isEqualTo("col\nvalue");
    verify(auditService).auditDigestCsv(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void getBalanceAsOf_parsesRequiredDate() {
    TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
    when(temporalBalanceService.getBalanceAsOfDate(9L, LocalDate.of(2026, 3, 31)))
        .thenReturn(new BigDecimal("42.00"));

    ApiResponse<BigDecimal> body =
        controllerWithTemporalBalanceService(temporalBalanceService)
            .getBalanceAsOf(9L, " 2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualByComparingTo("42.00");
    verify(temporalBalanceService).getBalanceAsOfDate(9L, LocalDate.of(2026, 3, 31));
  }

  @Test
  void getTrialBalanceAsOf_parsesRequiredDate() {
    TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
    TemporalBalanceService.TrialBalanceSnapshot expected =
        new TemporalBalanceService.TrialBalanceSnapshot(
            LocalDate.of(2026, 3, 31), List.of(), BigDecimal.ONE, BigDecimal.ONE);
    when(temporalBalanceService.getTrialBalanceAsOf(LocalDate.of(2026, 3, 31))).thenReturn(expected);

    ApiResponse<TemporalBalanceService.TrialBalanceSnapshot> body =
        controllerWithTemporalBalanceService(temporalBalanceService)
            .getTrialBalanceAsOf(" 2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(temporalBalanceService).getTrialBalanceAsOf(LocalDate.of(2026, 3, 31));
  }

  @Test
  void getAccountActivity_wrapsInvalidDateFormat() {
    AccountingController controller = controllerWithTemporalBalanceService(mock(TemporalBalanceService.class));

    assertThatThrownBy(() -> controller.getAccountActivity(9L, "bad", "2026-03-31", null, null))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_DATE);
              assertThat(applicationException.getDetails())
                  .containsEntry("startDate", "bad")
                  .containsEntry("endDate", "2026-03-31");
            });
  }

  @Test
  void getAccountActivity_parsesRequiredDates() {
    TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
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
            List.of());
    when(temporalBalanceService.getAccountActivity(
            9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<TemporalBalanceService.AccountActivityReport> body =
        controllerWithTemporalBalanceService(temporalBalanceService)
            .getAccountActivity(9L, " 2026-03-01 ", "2026-03-31 ", null, null)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(temporalBalanceService)
        .getAccountActivity(9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void compareBalances_parsesRequiredDates() {
    TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
    TemporalBalanceService.BalanceComparison expected =
        new TemporalBalanceService.BalanceComparison(
            9L,
            LocalDate.of(2026, 3, 1),
            new BigDecimal("10.00"),
            LocalDate.of(2026, 3, 31),
            new BigDecimal("12.00"),
            new BigDecimal("2.00"));
    when(temporalBalanceService.compareBalances(
            9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        .thenReturn(expected);

    ApiResponse<TemporalBalanceService.BalanceComparison> body =
        controllerWithTemporalBalanceService(temporalBalanceService)
            .compareBalances(9L, " 2026-03-01 ", "2026-03-31 ")
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(temporalBalanceService).compareBalances(9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
  }

  @Test
  void reverseJournalEntry_delegatesToJournalEntryService() {
    JournalEntryService journalEntryService = mock(JournalEntryService.class);
    AccountingController controller =
        newController(mock(AccountingService.class), journalEntryService, null);
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
    JournalEntryService journalEntryService = mock(JournalEntryService.class);
    AccountingController controller = newController(accountingService, journalEntryService, null);

    controller.listSalesReturns();

    verify(journalEntryService).listJournalEntriesByReferencePrefix("CRN-");
  }

  @Test
  void listSalesReturns_filtersOutCogsAndOrphanedCreditNotes() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalEntryService journalEntryService = mock(JournalEntryService.class);
    AccountingController controller = newController(accountingService, journalEntryService, null);
    JournalEntryDto legacySalesReturn = journalEntry(301L, "CRN-100", 11L, null, "Sales return");
    JournalEntryDto correctionLinkedSalesReturn =
        journalEntry(302L, "CRN-101", null, "SALES_RETURN", "Correction-linked sales return");
    JournalEntryDto cogsEntry = journalEntry(303L, "CRN-100-COGS-0", null, null, "COGS reversal");
    JournalEntryDto orphanedCreditNote =
        journalEntry(304L, "CRN-ORPHAN-1", null, null, "Orphaned credit note");
    when(journalEntryService.listJournalEntriesByReferencePrefix("CRN-"))
        .thenReturn(
            List.of(legacySalesReturn, correctionLinkedSalesReturn, cogsEntry, orphanedCreditNote));

    ApiResponse<List<JournalEntryDto>> body = controller.listSalesReturns().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).containsExactly(legacySalesReturn, correctionLinkedSalesReturn);
  }

  @Test
  void listSalesReturns_filtersNullBlankAndNonCrnEntries() {
    AccountingService accountingService = mock(AccountingService.class);
    JournalEntryService journalEntryService = mock(JournalEntryService.class);
    AccountingController controller = newController(accountingService, journalEntryService, null);
    JournalEntryDto blankReference = journalEntry(305L, "   ", 12L, null, "Blank reference");
    JournalEntryDto nonCrnReference =
        journalEntry(306L, "DN-200", 12L, "SALES_RETURN", "Wrong prefix");
    JournalEntryDto validSalesReturn =
        journalEntry(307L, "CRN-200", 12L, null, "Valid sales return");

    when(journalEntryService.listJournalEntriesByReferencePrefix("CRN-"))
        .thenReturn(
            java.util.Arrays.asList(null, blankReference, nonCrnReference, validSalesReturn));

    ApiResponse<List<JournalEntryDto>> body = controller.listSalesReturns().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).containsExactly(validSalesReturn);
  }

  @Test
  void previewSalesReturn_delegatesToSalesReturnService() {
    AccountingService accountingService = mock(AccountingService.class);
    SalesReturnService salesReturnService = mock(SalesReturnService.class);
    AccountingController controller =
        newController(accountingService, mock(JournalEntryService.class), salesReturnService);
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
    AccountingController controller =
        newController(accountingService, mock(JournalEntryService.class), null);
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
    AccountingController controller =
        newController(accountingService, mock(JournalEntryService.class), null);
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
  void settleDealer_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
    SettlementService settlementService = mock(SettlementService.class);
    AccountingController controller = controllerWithSettlementService(settlementService);
    DealerSettlementRequest request =
        new DealerSettlementRequest(
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
            null,
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
    when(settlementService.settleDealerInvoices(any(DealerSettlementRequest.class)))
        .thenReturn(expected);

    controller.settleDealer(request, "IDEMP-DEALER-HDR-1", null);

    ArgumentCaptor<DealerSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(DealerSettlementRequest.class);
    verify(settlementService).settleDealerInvoices(requestCaptor.capture());
    DealerSettlementRequest resolved = requestCaptor.getValue();
    assertThat(resolved.amount()).isEqualByComparingTo("180.00");
    assertThat(resolved.unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-DEALER-HDR-1");
  }

  @Test
  void settleSupplier_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
    SettlementService settlementService = mock(SettlementService.class);
    AccountingController controller = controllerWithSettlementService(settlementService);
    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
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
    when(settlementService.settleSupplierInvoices(any(SupplierSettlementRequest.class)))
        .thenReturn(expected);

    controller.settleSupplier(request, "IDEMP-SUP-HDR-1", null);

    ArgumentCaptor<SupplierSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(SupplierSettlementRequest.class);
    verify(settlementService).settleSupplierInvoices(requestCaptor.capture());
    SupplierSettlementRequest resolved = requestCaptor.getValue();
    assertThat(resolved.amount()).isEqualByComparingTo("95.00");
    assertThat(resolved.unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-SUP-HDR-1");
  }

  @Test
  void autoSettleSupplier_appliesHeaderOnlyIdempotencyKey() {
    SettlementService settlementService = mock(SettlementService.class);
    AccountingController controller = controllerWithSettlementService(settlementService);
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
    when(settlementService.autoSettleSupplier(eq(8L), any(AutoSettlementRequest.class)))
        .thenReturn(expected);

    controller.autoSettleSupplier(8L, request, "IDEMP-SUP-AUTO-1", null);

    ArgumentCaptor<AutoSettlementRequest> requestCaptor =
        ArgumentCaptor.forClass(AutoSettlementRequest.class);
    verify(settlementService).autoSettleSupplier(eq(8L), requestCaptor.capture());
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
        new AccountingFacade(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            mock(AccountingService.class),
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(CompanyEntityLookup.class),
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
    AccountingFacade facade =
        org.mockito.Mockito.spy(
            new AccountingFacade(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                mock(AccountingService.class),
                mock(JournalEntryRepository.class),
                mock(ReferenceNumberService.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyAccountingSettingsService.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class)));
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    doReturn(expectedJournal(703L, "MANUAL-FACADE-1"))
        .when(facade)
        .createStandardJournal(requestCaptor.capture());

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
    assertThat(requestCaptor.getValue().narration()).isEqualTo("manual facade journal");
  }

  @Test
  void accountingFacade_createManualJournal_forwardsAttachmentReferencesAndIdempotencyKey() {
    AccountingFacade facade =
        org.mockito.Mockito.spy(
            new AccountingFacade(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                mock(AccountingService.class),
                mock(JournalEntryRepository.class),
                mock(ReferenceNumberService.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyAccountingSettingsService.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class)));
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    doReturn(expectedJournal(704L, "MANUAL-FACADE-2"))
        .when(facade)
        .createStandardJournal(requestCaptor.capture());

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
        new AccountingFacade(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            mock(AccountingService.class),
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            mock(CompanyEntityLookup.class),
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

  private AccountingController newController(
      AccountingService accountingService,
      JournalEntryService journalEntryService,
      SalesReturnService salesReturnService) {
    return new AccountingController(
        accountingService,
        journalEntryService,
        null,
        null,
        null,
        null,
        null,
        null,
        salesReturnService,
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
        null);
  }

  private AccountingController controllerWithSettlementService(
      SettlementService settlementService) {
    return new AccountingController(
        mock(AccountingService.class),
        mock(JournalEntryService.class),
        null,
        settlementService,
        null,
        null,
        null,
        mock(AccountingFacade.class),
        mock(SalesReturnService.class),
        mock(AccountingPeriodService.class),
        mock(ReconciliationService.class),
        mock(StatementService.class),
        mock(TaxService.class),
        mock(TemporalBalanceService.class),
        mock(AccountHierarchyService.class),
        mock(AgingReportService.class),
        mock(CompanyDefaultAccountsService.class),
        mock(AccountingAuditTrailService.class),
        null,
        null,
        null,
        null);
  }

  private AccountingController controllerWithAuditTrailService(
      AccountingAuditTrailService accountingAuditTrailService) {
    return new AccountingController(
        mock(AccountingService.class),
        mock(JournalEntryService.class),
        null,
        null,
        null,
        null,
        null,
        null,
        mock(SalesReturnService.class),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        accountingAuditTrailService,
        null,
        null,
        null,
        null);
  }

  private AccountingController controllerWithStatementService(StatementService statementService) {
    return new AccountingController(
        mock(AccountingService.class),
        mock(JournalEntryService.class),
        null,
        null,
        null,
        null,
        null,
        null,
        mock(SalesReturnService.class),
        null,
        null,
        statementService,
        null,
        null,
        null,
        null,
        null,
        mock(AccountingAuditTrailService.class),
        null,
        null,
        null,
        null);
  }

  private AccountingController controllerWithTemporalBalanceService(
      TemporalBalanceService temporalBalanceService) {
    return new AccountingController(
        mock(AccountingService.class),
        mock(JournalEntryService.class),
        null,
        null,
        null,
        null,
        null,
        null,
        mock(SalesReturnService.class),
        null,
        null,
        mock(StatementService.class),
        null,
        temporalBalanceService,
        null,
        null,
        null,
        mock(AccountingAuditTrailService.class),
        null,
        null,
        null,
        null);
  }

  private AccountingController controllerWithAuditDigestService(AccountingAuditService accountingAuditService) {
    return new AccountingController(
        mock(AccountingService.class),
        mock(JournalEntryService.class),
        null,
        null,
        null,
        accountingAuditService,
        null,
        null,
        mock(SalesReturnService.class),
        null,
        null,
        mock(StatementService.class),
        null,
        null,
        null,
        null,
        null,
        mock(AccountingAuditTrailService.class),
        null,
        null,
        null,
        null);
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
