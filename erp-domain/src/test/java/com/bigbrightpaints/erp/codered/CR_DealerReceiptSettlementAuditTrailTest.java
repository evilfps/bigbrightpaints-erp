package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class CR_DealerReceiptSettlementAuditTrailTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private AccountingService accountingService;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Autowired private ReconciliationService reconciliationService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void dealerReceipt_fullSettlement_autoClosesInvoicedOrder() {
    String companyCode = "CR-AUTOCLOSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-AUTO",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    order.setStatus("INVOICED");
    salesOrderRepository.saveAndFlush(order);

    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    DealerReceiptRequest receiptRequest =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            outstanding,
            "DR-AUTO-" + UUID.randomUUID(),
            "Auto-close receipt",
            "DR-AUTO-IDEMP-" + UUID.randomUUID(),
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      accountingService.recordDealerReceipt(receiptRequest);
    } finally {
      CompanyContextHolder.clear();
    }

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("CLOSED");
    Integer autoCloseEvents =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from sales_order_status_history
            where company_id = ?
              and sales_order_id = ?
              and reason_code = 'ORDER_CLOSED_AUTO'
            """,
            Integer.class,
            company.getId(),
            order.getId());
    assertThat(autoCloseEvents).isNotNull();
    assertThat(autoCloseEvents).isGreaterThan(0);
    Integer salesAuditEvents =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from audit_action_events
            where company_id = ?
              and module = 'SALES'
              and action = 'ORDER_CLOSED_AUTO'
              and entity_type = 'SALES_ORDER'
              and entity_id = ?
            """,
            Integer.class,
            company.getId(),
            order.getId().toString());
    assertThat(salesAuditEvents).isNotNull();
    assertThat(salesAuditEvents).isGreaterThan(0);
  }

  @Test
  void dealerReceipt_isIdempotent_underConcurrency_andWritesAuditTrail() {
    String companyCode = "CR-AR-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DR-" + UUID.randomUUID();
    String idempotencyKey = "DR-IDEMP-" + UUID.randomUUID();

    DealerReceiptRequest receiptRequest =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            outstanding,
            referenceNumber,
            "CODE-RED receipt",
            idempotencyKey,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CoderedConcurrencyHarness.RunResult<JournalEntryDto> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.recordDealerReceipt(receiptRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("receipt calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
            .map(JournalEntryDto::id)
            .distinct()
            .toList();
    assertThat(journalIds).as("single receipt journal").hasSize(1);
    Long journalId = journalIds.getFirst();

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, referenceNumber))
        .as("single settlement allocation")
        .hasSize(0);
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("single settlement allocation")
        .hasSize(1);

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount())
        .as("invoice settled")
        .isEqualByComparingTo(BigDecimal.ZERO);

    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
    CoderedDbAssertions.assertDealerLedgerEntriesLinkedToJournal(
        jdbcTemplate, company.getId(), journalId);
    CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, journalId);

    CompanyContextHolder.setCompanyCode(companyCode);
    ReconciliationService.ReconciliationResult reconciliation =
        reconciliationService.reconcileArWithDealerLedger();
    CompanyContextHolder.clear();
    assertThat(reconciliation.isReconciled())
        .as(
            "ar reconciled (gl=%s ledger=%s variance=%s)",
            reconciliation.glArBalance(),
            reconciliation.dealerLedgerTotal(),
            reconciliation.variance())
        .isTrue();

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto retry = accountingService.recordDealerReceipt(receiptRequest);
      assertThat(retry.id()).as("retry returns same receipt").isEqualTo(journalId);
      BigDecimal halfOutstanding =
          outstanding.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
      DealerReceiptRequest conflictRequest =
          new DealerReceiptRequest(
              dealer.getId(),
              accounts.get("BANK").getId(),
              halfOutstanding,
              referenceNumber,
              "CODE-RED receipt",
              idempotencyKey,
              List.of(
                  new SettlementAllocationRequest(
                      invoice.getId(),
                      null,
                      halfOutstanding,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      null,
                      "Apply to invoice")));
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.recordDealerReceipt(conflictRequest))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .satisfies(
              error ->
                  org.assertj.core.api.Assertions.assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode.CONCURRENCY_CONFLICT));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void dealerSettlement_isIdempotent_underConcurrency_andRejectsMismatch() {
    String companyCode = "CR-SETTLE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DS-" + UUID.randomUUID();
    String idempotencyKey = "DS-IDEMP-" + UUID.randomUUID();

    PartnerSettlementRequest settlementRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            null,
            referenceNumber,
            "CODE-RED settlement",
            idempotencyKey,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CoderedConcurrencyHarness.RunResult<PartnerSettlementResponse> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.settleDealerInvoices(settlementRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("settlement calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<PartnerSettlementResponse>) outcome)
                        .value())
            .map(response -> response.journalEntry().id())
            .distinct()
            .toList();
    assertThat(journalIds).as("single settlement journal").hasSize(1);
    Long journalId = journalIds.getFirst();

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, referenceNumber))
        .as("reference number not used as idempotency key")
        .isEmpty();
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("single settlement allocation")
        .hasSize(1);

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount())
        .as("invoice settled")
        .isEqualByComparingTo(BigDecimal.ZERO);

    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
    CoderedDbAssertions.assertDealerLedgerEntriesLinkedToJournal(
        jdbcTemplate, company.getId(), journalId);
    CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, journalId);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse retry = accountingService.settleDealerInvoices(settlementRequest);
      assertThat(retry.journalEntry().id())
          .as("retry returns same settlement")
          .isEqualTo(journalId);
      BigDecimal halfOutstanding =
          outstanding.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
      PartnerSettlementRequest conflictRequest =
          new PartnerSettlementRequest(
              PartnerType.DEALER,
              dealer.getId(),
              accounts.get("BANK").getId(),
              null,
              null,
              null,
              null,
              null,
              referenceNumber,
              "CODE-RED settlement",
              idempotencyKey,
              Boolean.FALSE,
              List.of(
                  new SettlementAllocationRequest(
                      invoice.getId(),
                      null,
                      halfOutstanding,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      null,
                      "Apply to invoice")));
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.settleDealerInvoices(conflictRequest))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .satisfies(
              error ->
                  org.assertj.core.api.Assertions.assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode.CONCURRENCY_CONFLICT));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void dealerSettlement_isIdempotent_underConcurrency_withoutReferenceNumber() {
    String companyCode = "CR-SETTLE-NOREF-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String idempotencyKey = "DS-NOREF-IDEMP-" + UUID.randomUUID();

    PartnerSettlementRequest settlementRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            null,
            null,
            "CODE-RED settlement",
            idempotencyKey,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CoderedConcurrencyHarness.RunResult<PartnerSettlementResponse> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.settleDealerInvoices(settlementRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("settlement calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<PartnerSettlementResponse>) outcome)
                        .value())
            .map(response -> response.journalEntry().id())
            .distinct()
            .toList();
    assertThat(journalIds).as("single settlement journal").hasSize(1);
  }

  @Test
  void dealerSettlement_headerLevelFutureApplication_replaysWithoutDuplicateAllocations() {
    String companyCode = "CR-SET-HDR-FUT-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    String referenceNumber = "DS-HDR-FUT-" + UUID.randomUUID();
    String idempotencyKey = "DS-HDR-FUT-IDEMP-" + UUID.randomUUID();

    PartnerSettlementRequest settlementRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            new BigDecimal("120.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION,
            null,
            referenceNumber,
            "CODE-RED header settlement",
            idempotencyKey,
            Boolean.FALSE,
            null);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse first = accountingService.settleDealerInvoices(settlementRequest);
      PartnerSettlementResponse replay = accountingService.settleDealerInvoices(settlementRequest);

      assertThat(replay.journalEntry().id()).isEqualTo(first.journalEntry().id());
      assertThat(first.allocations()).hasSize(2);
      assertThat(first.allocations().get(0).invoiceId()).isEqualTo(invoice.getId());
      assertThat(first.allocations().get(1).applicationType())
          .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
      assertThat(first.allocations().get(1).invoiceId()).isNull();
    } finally {
      CompanyContextHolder.clear();
    }

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
                company, invoice))
        .as("single invoice allocation row for header settlement replay")
        .hasSize(1);
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("invoice allocation plus future-application row")
        .hasSize(2);
  }

  @Test
  void dealerSettlement_paymentEventFirst_linksJournalAndAllocations() {
    String companyCode = "CR-SET-PE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    Invoice invoice =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("90.00"),
            LocalDate.now().minusDays(7),
            LocalDate.now().plusDays(10),
            "CR-SET-PE");
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DS-PE-" + UUID.randomUUID();
    String idempotencyKey = "DS-PE-IDEMP-" + UUID.randomUUID();
    PartnerSettlementRequest request =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            null,
            referenceNumber,
            "CODE-RED payment-event settlement",
            idempotencyKey,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    Long journalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse first = accountingService.settleDealerInvoices(request);
      PartnerSettlementResponse replay = accountingService.settleDealerInvoices(request);
      journalId = first.journalEntry().id();
      assertThat(replay.journalEntry().id()).isEqualTo(journalId);
    } finally {
      CompanyContextHolder.clear();
    }

    Long paymentEventId =
        jdbcTemplate.queryForObject(
            """
            select id
            from partner_payment_events
            where company_id = ?
              and payment_flow = 'DEALER_SETTLEMENT'
              and source_route = '/api/v1/accounting/settlements/dealers'
              and lower(reference_number) = lower(?)
            """,
            Long.class,
            company.getId(),
            referenceNumber);
    assertThat(paymentEventId).isNotNull();

    Integer paymentEventCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from partner_payment_events
            where company_id = ?
              and payment_flow = 'DEALER_SETTLEMENT'
              and source_route = '/api/v1/accounting/settlements/dealers'
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(paymentEventCount).isEqualTo(1);

    Long linkedJournalId =
        jdbcTemplate.queryForObject(
            "select journal_entry_id from partner_payment_events where id = ?",
            Long.class,
            paymentEventId);
    assertThat(linkedJournalId).isEqualTo(journalId);

    Instant paymentEventCreatedAt =
        jdbcTemplate.queryForObject(
            "select created_at from partner_payment_events where id = ?",
            Instant.class,
            paymentEventId);
    Instant journalCreatedAt =
        jdbcTemplate.queryForObject(
            "select created_at from journal_entries where id = ?", Instant.class, journalId);
    assertThat(paymentEventCreatedAt).isNotNull();
    assertThat(journalCreatedAt).isNotNull();
    assertThat(paymentEventCreatedAt).isBeforeOrEqualTo(journalCreatedAt);

    List<Long> allocationPaymentEventIds =
        jdbcTemplate.queryForList(
            """
            select payment_event_id
            from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            order by created_at asc, id asc
            """,
            Long.class,
            company.getId(),
            idempotencyKey);
    assertThat(allocationPaymentEventIds).isNotEmpty();
    assertThat(allocationPaymentEventIds).allMatch(id -> id != null && id.equals(paymentEventId));
  }

  @Test
  void dealerAutoSettle_usesSettlementPaymentFlow_andAllocatesOldestFirst() {
    String companyCode = "CR-AUTO-SET-PE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    Invoice earliestDue =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("20.00"),
            LocalDate.now().minusDays(12),
            LocalDate.now().plusDays(1),
            "CR-AUTO-SET-1");
    Invoice sameDueOlderInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("30.00"),
            LocalDate.now().minusDays(10),
            LocalDate.now().plusDays(2),
            "CR-AUTO-SET-2");
    Invoice sameDueNewerInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("25.00"),
            LocalDate.now().minusDays(3),
            LocalDate.now().plusDays(2),
            "CR-AUTO-SET-3");

    String referenceNumber = "DAS-PE-" + UUID.randomUUID();
    String idempotencyKey = "DAS-PE-IDEMP-" + UUID.randomUUID();
    AutoSettlementRequest request =
        new AutoSettlementRequest(
            accounts.get("BANK").getId(),
            new BigDecimal("20.00"),
            referenceNumber,
            "CODE-RED auto settlement",
            idempotencyKey);

    Long journalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse first = accountingService.autoSettleDealer(dealer.getId(), request);
      PartnerSettlementResponse replay =
          accountingService.autoSettleDealer(dealer.getId(), request);
      journalId = first.journalEntry().id();
      assertThat(replay.journalEntry().id()).isEqualTo(journalId);
    } finally {
      CompanyContextHolder.clear();
    }

    Long paymentEventId =
        jdbcTemplate.queryForObject(
            """
            select id
            from partner_payment_events
            where company_id = ?
              and payment_flow = 'DEALER_SETTLEMENT'
              and source_route = '/api/v1/accounting/dealers/{dealerId}/auto-settle'
              and lower(reference_number) = lower(?)
            """,
            Long.class,
            company.getId(),
            referenceNumber);
    assertThat(paymentEventId).isNotNull();

    Long linkedJournalId =
        jdbcTemplate.queryForObject(
            "select journal_entry_id from partner_payment_events where id = ?",
            Long.class,
            paymentEventId);
    assertThat(linkedJournalId).isEqualTo(journalId);

    List<Map<String, Object>> allocationRows =
        jdbcTemplate.queryForList(
            """
            select invoice_id, allocation_amount, payment_event_id
            from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            order by created_at asc, id asc
            """,
            company.getId(),
            idempotencyKey);
    assertThat(allocationRows).hasSize(1);

    assertThat(((Number) allocationRows.get(0).get("invoice_id")).longValue())
        .isEqualTo(earliestDue.getId());
    assertThat(new BigDecimal(allocationRows.get(0).get("allocation_amount").toString()))
        .isEqualByComparingTo(new BigDecimal("20.00"));

    assertThat(allocationRows)
        .allSatisfy(
            row ->
                assertThat(((Number) row.get("payment_event_id")).longValue())
                    .isEqualTo(paymentEventId));

    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, earliestDue.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueOlderInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(new BigDecimal("30.00"));
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueNewerInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(new BigDecimal("25.00"));
  }

  @Test
  void dealerAutoSettle_keylessSuccessiveSameAmount_settlesNextOldestInvoice() {
    String companyCode = "CR-AUTO-SET-KEYLESS-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    Invoice oldestInvoice =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("20.00"),
            LocalDate.now().minusDays(14),
            LocalDate.now().plusDays(1),
            "CR-AUTO-SET-KEYLESS-1");
    Invoice nextOldestInvoice =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("20.00"),
            LocalDate.now().minusDays(10),
            LocalDate.now().plusDays(2),
            "CR-AUTO-SET-KEYLESS-2");

    AutoSettlementRequest keylessRequest =
        new AutoSettlementRequest(
            accounts.get("BANK").getId(),
            new BigDecimal("20.00"),
            null,
            "CODE-RED keyless dealer auto-settlement",
            null);

    Long firstJournalId;
    Long secondJournalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse first =
          accountingService.autoSettleDealer(dealer.getId(), keylessRequest);
      PartnerSettlementResponse second =
          accountingService.autoSettleDealer(dealer.getId(), keylessRequest);

      firstJournalId = first.journalEntry().id();
      secondJournalId = second.journalEntry().id();
      assertThat(firstJournalId).isNotNull();
      assertThat(secondJournalId).isNotNull();
      assertThat(secondJournalId).isNotEqualTo(firstJournalId);
      assertThat(first.allocations()).hasSize(1);
      assertThat(second.allocations()).hasSize(1);
      assertThat(first.allocations().get(0).invoiceId()).isEqualTo(oldestInvoice.getId());
      assertThat(second.allocations().get(0).invoiceId()).isEqualTo(nextOldestInvoice.getId());
    } finally {
      CompanyContextHolder.clear();
    }

    List<Map<String, Object>> allocationRows =
        jdbcTemplate.queryForList(
            """
            select invoice_id, idempotency_key
            from partner_settlement_allocations
            where company_id = ?
              and dealer_id = ?
            order by created_at asc, id asc
            """,
            company.getId(),
            dealer.getId());
    assertThat(allocationRows).hasSize(2);
    assertThat(((Number) allocationRows.get(0).get("invoice_id")).longValue())
        .isEqualTo(oldestInvoice.getId());
    assertThat(((Number) allocationRows.get(1).get("invoice_id")).longValue())
        .isEqualTo(nextOldestInvoice.getId());
    assertThat(allocationRows.get(0).get("idempotency_key").toString())
        .isNotEqualToIgnoringCase(allocationRows.get(1).get("idempotency_key").toString());

    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, oldestInvoice.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, nextOldestInvoice.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void
      dealerSettlement_reusedReferenceWithDifferentIdempotencyKey_failsClosedWithoutDuplicateAllocations() {
    String companyCode = "CR-SET-REF-REUSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DS-REF-REUSE-" + UUID.randomUUID();

    PartnerSettlementRequest firstRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            null,
            referenceNumber,
            "CODE-RED settlement",
            "DS-KEY-1-" + UUID.randomUUID(),
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    PartnerSettlementRequest secondRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            accounts.get("BANK").getId(),
            null,
            null,
            null,
            null,
            null,
            referenceNumber,
            "CODE-RED settlement",
            "DS-KEY-2-" + UUID.randomUUID(),
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      PartnerSettlementResponse first = accountingService.settleDealerInvoices(firstRequest);
      assertThat(first.journalEntry().id()).isNotNull();
      long retryStartedAtNanos = System.nanoTime();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.settleDealerInvoices(secondRequest))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .hasMessageContaining("Settlement allocation exceeds invoice outstanding amount");
      Duration retryLatency = Duration.ofNanos(System.nanoTime() - retryStartedAtNanos);
      assertThat(retryLatency).isLessThan(Duration.ofSeconds(5));
    } finally {
      CompanyContextHolder.clear();
    }

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
                company, invoice))
        .as("single allocation row for reused settlement reference")
        .hasSize(1);
  }

  @Test
  void dealerReceiptSplit_isIdempotent_underConcurrency_andWritesAuditTrail() {
    String companyCode = "CR-DRS-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    BigDecimal bankAmount =
        outstanding.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
    BigDecimal cashAmount = outstanding.subtract(bankAmount);
    String referenceNumber = "DRS-" + UUID.randomUUID();
    String idempotencyKey = "DRS-IDEMP-" + UUID.randomUUID();

    DealerReceiptSplitRequest receiptRequest =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), bankAmount),
                new DealerReceiptSplitRequest.IncomingLine(cash.getId(), cashAmount)),
            referenceNumber,
            "CODE-RED split receipt",
            idempotencyKey);

    CoderedConcurrencyHarness.RunResult<JournalEntryDto> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.recordDealerReceiptSplit(receiptRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("split receipt calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
            .map(JournalEntryDto::id)
            .distinct()
            .toList();
    assertThat(journalIds).as("single split receipt journal").hasSize(1);
    Long journalId = journalIds.getFirst();

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, referenceNumber))
        .as("reference number not used as idempotency key")
        .isEmpty();
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("single split settlement allocation")
        .hasSize(1);

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount())
        .as("invoice settled")
        .isEqualByComparingTo(BigDecimal.ZERO);

    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
    CoderedDbAssertions.assertDealerLedgerEntriesLinkedToJournal(
        jdbcTemplate, company.getId(), journalId);
    CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, journalId);

    CompanyContextHolder.setCompanyCode(companyCode);
    ReconciliationService.ReconciliationResult reconciliation =
        reconciliationService.reconcileArWithDealerLedger();
    CompanyContextHolder.clear();
    assertThat(reconciliation.isReconciled())
        .as(
            "ar reconciled (gl=%s ledger=%s variance=%s)",
            reconciliation.glArBalance(),
            reconciliation.dealerLedgerTotal(),
            reconciliation.variance())
        .isTrue();

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto retry = accountingService.recordDealerReceiptSplit(receiptRequest);
      assertThat(retry.id()).as("retry returns same split receipt").isEqualTo(journalId);
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void dealerReceiptSplit_zeroAllocationReplay_returnsOriginalJournal() {
    String companyCode = "CR-DRS-ZERO-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));
    String referenceNumber = "DRS-ZERO-" + UUID.randomUUID();
    String idempotencyKey = "DRS-ZERO-IDEMP-" + UUID.randomUUID();

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), new BigDecimal("90.00")),
                new DealerReceiptSplitRequest.IncomingLine(cash.getId(), new BigDecimal("10.00"))),
            referenceNumber,
            "CODE-RED zero-allocation split receipt",
            idempotencyKey);

    Long journalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceiptSplit(request);
      JournalEntryDto replay = accountingService.recordDealerReceiptSplit(request);
      journalId = first.id();
      assertThat(replay.id())
          .as("replay keeps original split receipt journal")
          .isEqualTo(journalId);
    } finally {
      CompanyContextHolder.clear();
    }

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, referenceNumber))
        .as("reference number never persists as idempotency key")
        .isEmpty();
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("zero-allocation split receipt records explicit unapplied allocation truth")
        .hasSize(1);
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
    CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, journalId);
  }

  @Test
  void dealerReceipt_paymentEventFirst_linksJournalAndExplicitOnAccountAllocation() {
    String companyCode = "CR-DR-PAYMENT-EVENT-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    String referenceNumber = "DR-PE-" + UUID.randomUUID();
    String idempotencyKey = "DR-PE-IDEMP-" + UUID.randomUUID();
    BigDecimal receiptAmount = new BigDecimal("125.00");
    DealerReceiptRequest request =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            receiptAmount,
            referenceNumber,
            "CODE-RED payment-event receipt",
            idempotencyKey,
            List.of());

    Long journalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceipt(request);
      JournalEntryDto replay = accountingService.recordDealerReceipt(request);
      journalId = first.id();
      assertThat(replay.id()).isEqualTo(journalId);
    } finally {
      CompanyContextHolder.clear();
    }

    Long paymentEventId =
        jdbcTemplate.queryForObject(
            """
            select id
            from partner_payment_events
            where company_id = ?
              and payment_flow = 'DEALER_RECEIPT'
              and lower(reference_number) = lower(?)
            """,
            Long.class,
            company.getId(),
            referenceNumber);
    assertThat(paymentEventId).isNotNull();

    Long linkedJournalId =
        jdbcTemplate.queryForObject(
            "select journal_entry_id from partner_payment_events where id = ?",
            Long.class,
            paymentEventId);
    assertThat(linkedJournalId).isEqualTo(journalId);

    Integer paymentEventCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from partner_payment_events
            where company_id = ?
              and payment_flow = 'DEALER_RECEIPT'
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(paymentEventCount).isEqualTo(1);

    Instant paymentEventCreatedAt =
        jdbcTemplate.queryForObject(
            "select created_at from partner_payment_events where id = ?",
            Instant.class,
            paymentEventId);
    Instant journalCreatedAt =
        jdbcTemplate.queryForObject(
            "select created_at from journal_entries where id = ?", Instant.class, journalId);
    assertThat(paymentEventCreatedAt).isNotNull();
    assertThat(journalCreatedAt).isNotNull();
    assertThat(paymentEventCreatedAt).isBeforeOrEqualTo(journalCreatedAt);

    BigDecimal unappliedAmount =
        jdbcTemplate.queryForObject(
            """
            select allocation_amount
            from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
              and invoice_id is null
              and payment_event_id = ?
            """,
            BigDecimal.class,
            company.getId(),
            idempotencyKey,
            paymentEventId);
    assertThat(unappliedAmount).isEqualByComparingTo(receiptAmount);
  }

  @Test
  void dealerReceipt_replayFailsClosed_whenAllocationRowsAreMissing() {
    String companyCode = "CR-DR-MISSING-ALLOC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    String referenceNumber = "DR-MISSING-" + UUID.randomUUID();
    String idempotencyKey = "DR-MISSING-IDEMP-" + UUID.randomUUID();
    DealerReceiptRequest request =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            new BigDecimal("125.00"),
            referenceNumber,
            "CODE-RED degraded replay receipt",
            idempotencyKey,
            List.of());

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceipt(request);
      assertThat(first.id()).isNotNull();
    } finally {
      CompanyContextHolder.clear();
    }

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("first write persists explicit allocation truth")
        .hasSize(1);
    Integer deleted =
        jdbcTemplate.update(
            """
            delete from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            """,
            company.getId(),
            idempotencyKey);
    assertThat(deleted).isEqualTo(1);
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("degraded replay fixture has no allocation rows")
        .isEmpty();

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.recordDealerReceipt(request))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .hasMessageContaining("allocation not found")
          .satisfies(
              error ->
                  org.assertj.core.api.Assertions.assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode
                              .INTERNAL_CONCURRENCY_FAILURE));
    } finally {
      CompanyContextHolder.clear();
    }

    Integer journalCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from journal_entries
            where company_id = ?
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(journalCount).isEqualTo(1);
  }

  @Test
  void dealerReceiptSplit_replayFailsClosed_whenAllocationRowsAreMissing() {
    String companyCode = "CR-DRS-MISSING-ALLOC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    String referenceNumber = "DRS-MISSING-" + UUID.randomUUID();
    String idempotencyKey = "DRS-MISSING-IDEMP-" + UUID.randomUUID();
    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), new BigDecimal("125.00"))),
            referenceNumber,
            "CODE-RED degraded replay split receipt",
            idempotencyKey);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceiptSplit(request);
      assertThat(first.id()).isNotNull();
    } finally {
      CompanyContextHolder.clear();
    }

    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("first split receipt write persists explicit allocation truth")
        .hasSize(1);
    Integer deleted =
        jdbcTemplate.update(
            """
            delete from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            """,
            company.getId(),
            idempotencyKey);
    assertThat(deleted).isEqualTo(1);
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("degraded split replay fixture has no allocation rows")
        .isEmpty();

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.recordDealerReceiptSplit(request))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .hasMessageContaining("allocation not found")
          .satisfies(
              error ->
                  org.assertj.core.api.Assertions.assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode
                              .INTERNAL_CONCURRENCY_FAILURE));
    } finally {
      CompanyContextHolder.clear();
    }

    Integer journalCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from journal_entries
            where company_id = ?
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(journalCount).isEqualTo(1);
  }

  @Test
  void dealerReceiptSplit_replayFailsClosed_whenOnlySubsetOfAllocationRowsSurvives() {
    String companyCode = "CR-DRS-SUBSET-ALLOC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    Invoice earliestDue =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("20.00"),
            LocalDate.now().minusDays(12),
            LocalDate.now().plusDays(1),
            "CR-DRS-SUBSET-1");
    Invoice sameDueOlderInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("30.00"),
            LocalDate.now().minusDays(10),
            LocalDate.now().plusDays(2),
            "CR-DRS-SUBSET-2");
    Invoice sameDueNewerInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("25.00"),
            LocalDate.now().minusDays(3),
            LocalDate.now().plusDays(2),
            "CR-DRS-SUBSET-3");

    String referenceNumber = "DRS-SUBSET-" + UUID.randomUUID();
    String idempotencyKey = "DRS-SUBSET-IDEMP-" + UUID.randomUUID();
    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), new BigDecimal("100.00")),
                new DealerReceiptSplitRequest.IncomingLine(cash.getId(), new BigDecimal("5.00"))),
            referenceNumber,
            "CODE-RED degraded subset split receipt",
            idempotencyKey);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceiptSplit(request);
      assertThat(first.id()).isNotNull();
    } finally {
      CompanyContextHolder.clear();
    }

    List<Map<String, Object>> allocationRows =
        jdbcTemplate.queryForList(
            """
            select id, invoice_id
            from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            order by created_at asc, id asc
            """,
            company.getId(),
            idempotencyKey);
    assertThat(allocationRows).hasSize(4);

    Integer deleted =
        jdbcTemplate.update(
            """
            delete from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
              and invoice_id = ?
            """,
            company.getId(),
            idempotencyKey,
            sameDueOlderInvoiceDate.getId());
    assertThat(deleted).isEqualTo(1);
    assertThat(
            settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .as("degraded split replay fixture keeps only a subset of canonical allocation rows")
        .hasSize(3);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.recordDealerReceiptSplit(request))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .hasMessageContaining("allocation")
          .satisfies(
              error ->
                  org.assertj.core.api.Assertions.assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode
                              .INTERNAL_CONCURRENCY_FAILURE));
    } finally {
      CompanyContextHolder.clear();
    }

    Integer journalCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from journal_entries
            where company_id = ?
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(journalCount).isEqualTo(1);

    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, earliestDue.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueOlderInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueNewerInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void dealerReceipt_rejectsCrossDealerInvoiceAllocation() {
    String companyCode = "CR-DR-CROSS-DEALER-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"), "CR-DEALER-A", "Code-Red Dealer A");
    Dealer foreignDealer =
        ensureDealer(company, accounts.get("AR"), "CR-DEALER-B", "Code-Red Dealer B");

    Invoice foreignInvoice =
        createStandaloneInvoice(
            company,
            foreignDealer,
            new BigDecimal("90.00"),
            LocalDate.now().minusDays(5),
            LocalDate.now().plusDays(10),
            "CR-DR-CROSS");
    String referenceNumber = "DR-CROSS-" + UUID.randomUUID();
    String idempotencyKey = "DR-CROSS-IDEMP-" + UUID.randomUUID();
    DealerReceiptRequest request =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            new BigDecimal("90.00"),
            referenceNumber,
            "CODE-RED cross dealer receipt",
            idempotencyKey,
            List.of(
                new SettlementAllocationRequest(
                    foreignInvoice.getId(),
                    null,
                    new BigDecimal("90.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Cross dealer allocation")));

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> accountingService.recordDealerReceipt(request))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .hasMessageContaining("Invoice does not belong to the dealer");
    } finally {
      CompanyContextHolder.clear();
    }

    Integer persistedJournals =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from journal_entries
            where company_id = ?
              and lower(reference_number) = lower(?)
            """,
            Integer.class,
            company.getId(),
            referenceNumber);
    assertThat(persistedJournals).isZero();
  }

  @Test
  void dealerReceiptSplit_autoAllocatesDeterministically_andPersistsUnappliedRemainder() {
    String companyCode = "CR-DRS-FIFO-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    Invoice earliestDue =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("20.00"),
            LocalDate.now().minusDays(12),
            LocalDate.now().plusDays(1),
            "CR-DRS-FIFO-1");
    Invoice sameDueOlderInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("30.00"),
            LocalDate.now().minusDays(10),
            LocalDate.now().plusDays(2),
            "CR-DRS-FIFO-2");
    Invoice sameDueNewerInvoiceDate =
        createStandaloneInvoice(
            company,
            dealer,
            new BigDecimal("25.00"),
            LocalDate.now().minusDays(3),
            LocalDate.now().plusDays(2),
            "CR-DRS-FIFO-3");

    String referenceNumber = "DRS-FIFO-" + UUID.randomUUID();
    String idempotencyKey = "DRS-FIFO-IDEMP-" + UUID.randomUUID();
    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), new BigDecimal("100.00")),
                new DealerReceiptSplitRequest.IncomingLine(cash.getId(), new BigDecimal("5.00"))),
            referenceNumber,
            "CODE-RED FIFO split receipt",
            idempotencyKey);

    Long journalId;
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto response = accountingService.recordDealerReceiptSplit(request);
      journalId = response.id();
    } finally {
      CompanyContextHolder.clear();
    }

    List<Map<String, Object>> allocationRows =
        jdbcTemplate.queryForList(
            """
            select invoice_id, allocation_amount, payment_event_id
            from partner_settlement_allocations
            where company_id = ?
              and lower(idempotency_key) = lower(?)
            order by created_at asc, id asc
            """,
            company.getId(),
            idempotencyKey);
    assertThat(allocationRows).hasSize(4);

    assertThat(((Number) allocationRows.get(0).get("invoice_id")).longValue())
        .isEqualTo(earliestDue.getId());
    assertThat(new BigDecimal(allocationRows.get(0).get("allocation_amount").toString()))
        .isEqualByComparingTo(new BigDecimal("20.00"));

    assertThat(((Number) allocationRows.get(1).get("invoice_id")).longValue())
        .isEqualTo(sameDueOlderInvoiceDate.getId());
    assertThat(new BigDecimal(allocationRows.get(1).get("allocation_amount").toString()))
        .isEqualByComparingTo(new BigDecimal("30.00"));

    assertThat(((Number) allocationRows.get(2).get("invoice_id")).longValue())
        .isEqualTo(sameDueNewerInvoiceDate.getId());
    assertThat(new BigDecimal(allocationRows.get(2).get("allocation_amount").toString()))
        .isEqualByComparingTo(new BigDecimal("25.00"));

    assertThat(allocationRows.get(3).get("invoice_id")).isNull();
    assertThat(new BigDecimal(allocationRows.get(3).get("allocation_amount").toString()))
        .isEqualByComparingTo(new BigDecimal("30.00"));

    Long paymentEventId = ((Number) allocationRows.get(0).get("payment_event_id")).longValue();
    assertThat(paymentEventId).isNotNull();
    assertThat(allocationRows)
        .allSatisfy(
            row ->
                assertThat(((Number) row.get("payment_event_id")).longValue())
                    .isEqualTo(paymentEventId));

    Long linkedJournalId =
        jdbcTemplate.queryForObject(
            "select journal_entry_id from partner_payment_events where id = ?",
            Long.class,
            paymentEventId);
    assertThat(linkedJournalId).isEqualTo(journalId);

    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, earliestDue.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueOlderInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            invoiceRepository
                .findByCompanyAndId(company, sameDueNewerInvoiceDate.getId())
                .orElseThrow()
                .getOutstandingAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void dealerReceipt_reusedReferenceWithDifferentIdempotencyKey_doesNotDuplicateAllocations() {
    String companyCode = "CR-DR-REF-REUSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DR-REF-REUSE-" + UUID.randomUUID();

    DealerReceiptRequest firstRequest =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            outstanding,
            referenceNumber,
            "CODE-RED receipt",
            "DR-KEY-1-" + UUID.randomUUID(),
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    DealerReceiptRequest secondRequest =
        new DealerReceiptRequest(
            dealer.getId(),
            accounts.get("BANK").getId(),
            outstanding,
            referenceNumber,
            "CODE-RED receipt",
            "DR-KEY-2-" + UUID.randomUUID(),
            List.of(
                new SettlementAllocationRequest(
                    invoice.getId(),
                    null,
                    outstanding,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    "Apply to invoice")));

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceipt(firstRequest);
      JournalEntryDto second = accountingService.recordDealerReceipt(secondRequest);
      long retryStartedAtNanos = System.nanoTime();
      JournalEntryDto secondRetry = accountingService.recordDealerReceipt(secondRequest);
      Duration retryLatency = Duration.ofNanos(System.nanoTime() - retryStartedAtNanos);
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(secondRetry.id()).isEqualTo(first.id());
      assertThat(retryLatency).isLessThan(Duration.ofSeconds(5));
    } finally {
      CompanyContextHolder.clear();
    }

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
                company, invoice))
        .as("single allocation row for reused reference")
        .hasSize(1);
  }

  @Test
  void
      dealerReceiptSplit_reusedReferenceWithDifferentIdempotencyKey_replaysWithoutDuplicateAllocations() {
    String companyCode = "CR-DRS-REF-REUSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    registerFinishedGoodBatchForTest(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    Invoice invoice = issueInvoiceForOrder(company, dealer, order);
    BigDecimal outstanding = invoice.getOutstandingAmount();
    String referenceNumber = "DRS-REF-REUSE-" + UUID.randomUUID();

    DealerReceiptSplitRequest firstRequest =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), outstanding)),
            referenceNumber,
            "CODE-RED split receipt",
            "DRS-KEY-1-" + UUID.randomUUID());

    DealerReceiptSplitRequest secondRequest =
        new DealerReceiptSplitRequest(
            dealer.getId(),
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(
                    accounts.get("BANK").getId(), outstanding)),
            referenceNumber,
            "CODE-RED split receipt",
            "DRS-KEY-2-" + UUID.randomUUID());

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.recordDealerReceiptSplit(firstRequest);
      JournalEntryDto second = accountingService.recordDealerReceiptSplit(secondRequest);
      long retryStartedAtNanos = System.nanoTime();
      JournalEntryDto secondRetry = accountingService.recordDealerReceiptSplit(secondRequest);
      Duration retryLatency = Duration.ofNanos(System.nanoTime() - retryStartedAtNanos);
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(secondRetry.id()).isEqualTo(first.id());
      assertThat(retryLatency).isLessThan(Duration.ofSeconds(5));
    } finally {
      CompanyContextHolder.clear();
    }

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
                company, invoice))
        .as("single allocation row for reused split receipt reference")
        .hasSize(1);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    companyRepository.save(company);
    return company;
  }

  private Map<String, Account> ensureCoreAccounts(Company company) {
    Account ar = ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
    Account inv = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST_OUT", "GST Output", AccountType.LIABILITY);
    Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);

    updateCompanyDefaults(company, inv, cogs, rev, disc, gstOut);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "BANK", bank);
  }

  private void updateCompanyDefaults(
      Company company, Account inv, Account cogs, Account rev, Account disc, Account gstOut) {
    if (company == null || company.getId() == null) {
      return;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      Company fresh = companyRepository.findById(company.getId()).orElseThrow();
      boolean updated = false;
      if (fresh.getDefaultInventoryAccountId() == null) {
        fresh.setDefaultInventoryAccountId(inv.getId());
        updated = true;
      }
      if (fresh.getDefaultCogsAccountId() == null) {
        fresh.setDefaultCogsAccountId(cogs.getId());
        updated = true;
      }
      if (fresh.getDefaultRevenueAccountId() == null) {
        fresh.setDefaultRevenueAccountId(rev.getId());
        updated = true;
      }
      if (fresh.getDefaultDiscountAccountId() == null) {
        fresh.setDefaultDiscountAccountId(disc.getId());
        updated = true;
      }
      if (fresh.getDefaultTaxAccountId() == null) {
        fresh.setDefaultTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstOutputTaxAccountId() == null) {
        fresh.setGstOutputTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstPayableAccountId() == null) {
        fresh.setGstPayableAccountId(gstOut.getId());
        updated = true;
      }
      if (!updated) {
        return;
      }
      try {
        companyRepository.save(fresh);
        return;
      } catch (ObjectOptimisticLockingFailureException ex) {
        if (attempt == 1) {
          throw ex;
        }
      }
    }
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private Dealer ensureDealer(Company company, Account arAccount) {
    return ensureDealer(company, arAccount, "CR-DEALER", "Code-Red Dealer");
  }

  private Dealer ensureDealer(Company company, Account arAccount, String code, String name) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .map(
            existing -> {
              if (existing.getReceivableAccount() == null) {
                existing.setReceivableAccount(arAccount);
                return dealerRepository.save(existing);
              }
              return existing;
            })
        .orElseGet(
            () -> {
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setCode(code);
              dealer.setName(name);
              dealer.setStatus("ACTIVE");
              dealer.setReceivableAccount(arAccount);
              return dealerRepository.save(dealer);
            });
  }

  private FinishedGood ensureFinishedGoodWithCatalog(
      Company company, Map<String, Account> accounts, String sku, BigDecimal gstRate) {
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            sku,
            sku + " Name",
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("GST_OUT").getId());
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  CompanyContextHolder.setCompanyCode(company.getCode());
                  var dto = createFinishedGoodForTest(req);
                  CompanyContextHolder.clear();
                  return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    ensureCatalogProduct(company, fg, gstRate);
    return fg;
  }

  private void ensureCatalogProduct(Company company, FinishedGood fg, BigDecimal gstRate) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "CR-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(company);
                  b.setCode("CR-BRAND");
                  b.setName("Code-Red Brand");
                  return productionBrandRepository.save(b);
                });
    productionProductRepository
        .findByCompanyAndSkuCode(company, fg.getProductCode())
        .orElseGet(
            () -> {
              ProductionProduct p = new ProductionProduct();
              p.setCompany(company);
              p.setBrand(brand);
              p.setSkuCode(fg.getProductCode());
              p.setProductName(fg.getName());
              p.setBasePrice(new BigDecimal("10.00"));
              p.setCategory("GENERAL");
              p.setSizeLabel("STD");
              p.setDefaultColour("NA");
              p.setMinDiscountPercent(BigDecimal.ZERO);
              p.setMinSellingPrice(BigDecimal.ZERO);
              p.setMetadata(new java.util.HashMap<>());
              p.setGstRate(gstRate);
              p.setUnitOfMeasure("UNIT");
              p.setActive(true);
              return productionProductRepository.save(p);
            });
  }

  private SalesOrder createOrder(
      Company company,
      Dealer dealer,
      String productCode,
      BigDecimal quantity,
      BigDecimal unitPrice) {
    CompanyContextHolder.setCompanyCode(company.getCode());
    BigDecimal totalAmount =
        unitPrice.multiply(quantity).setScale(2, java.math.RoundingMode.HALF_UP);
    var orderDto =
        salesService.createOrder(
            new SalesOrderRequest(
                dealer.getId(),
                totalAmount,
                "INR",
                "CODE-RED order",
                List.of(
                    new SalesOrderItemRequest(
                        productCode, "Item", quantity, unitPrice, BigDecimal.ZERO)),
                "EXCLUSIVE",
                null,
                null,
                UUID.randomUUID().toString()));
    CompanyContextHolder.clear();
    return salesOrderRepository.findById(orderDto.id()).orElseThrow();
  }

  private Invoice issueInvoiceForOrder(Company company, Dealer dealer, SalesOrder order) {
    Optional<Invoice> existing =
        invoiceRepository.findByCompanyAndSalesOrderId(company, order.getId());
    if (existing.isPresent()) {
      return existing.get();
    }

    Invoice fallback = new Invoice();
    fallback.setCompany(company);
    fallback.setDealer(dealer);
    fallback.setSalesOrder(order);
    fallback.setStatus("ISSUED");
    fallback.setCurrency(company.getBaseCurrency() != null ? company.getBaseCurrency() : "INR");
    fallback.setIssueDate(LocalDate.now());
    fallback.setDueDate(LocalDate.now().plusDays(30));
    BigDecimal totalAmount =
        order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
    fallback.setTotalAmount(totalAmount);
    fallback.setOutstandingAmount(totalAmount);
    fallback.setInvoiceNumber("TEST-INV-" + order.getId());
    return invoiceRepository.save(fallback);
  }

  private Invoice createStandaloneInvoice(
      Company company,
      Dealer dealer,
      BigDecimal amount,
      LocalDate issueDate,
      LocalDate dueDate,
      String invoicePrefix) {
    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setStatus("POSTED");
    invoice.setCurrency(company.getBaseCurrency() != null ? company.getBaseCurrency() : "INR");
    invoice.setIssueDate(issueDate);
    invoice.setDueDate(dueDate);
    invoice.setSubtotal(amount);
    invoice.setTaxTotal(BigDecimal.ZERO);
    invoice.setTotalAmount(amount);
    invoice.setOutstandingAmount(amount);
    invoice.setInvoiceNumber(invoicePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    invoice.setNotes("Code-red standalone invoice");
    return invoiceRepository.saveAndFlush(invoice);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
