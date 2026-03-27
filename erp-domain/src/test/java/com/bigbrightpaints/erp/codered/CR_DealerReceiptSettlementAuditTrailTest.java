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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
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
  void dealerReceipt_isIdempotent_underConcurrency_andWritesAuditTrail() {
    String companyCode = "CR-AR-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
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
    finishedGoodsService.registerBatch(
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

    DealerSettlementRequest settlementRequest =
        new DealerSettlementRequest(
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
                    "Apply to invoice")),
            null);

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
      DealerSettlementRequest conflictRequest =
          new DealerSettlementRequest(
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
                      "Apply to invoice")),
              null);
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
    finishedGoodsService.registerBatch(
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

    DealerSettlementRequest settlementRequest =
        new DealerSettlementRequest(
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
                    "Apply to invoice")),
            null);

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
    finishedGoodsService.registerBatch(
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

    DealerSettlementRequest settlementRequest =
        new DealerSettlementRequest(
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
            null,
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
  void
      dealerSettlement_reusedReferenceWithDifferentIdempotencyKey_failsClosedWithoutDuplicateAllocations() {
    String companyCode = "CR-SET-REF-REUSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
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

    DealerSettlementRequest firstRequest =
        new DealerSettlementRequest(
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
                    "Apply to invoice")),
            null);

    DealerSettlementRequest secondRequest =
        new DealerSettlementRequest(
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
                    "Apply to invoice")),
            null);

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
  void dealerReceipt_reusedReferenceWithDifferentIdempotencyKey_doesNotDuplicateAllocations() {
    String companyCode = "CR-DR-REF-REUSE-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
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
    finishedGoodsService.registerBatch(
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
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, "CR-DEALER")
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
              dealer.setCode("CR-DEALER");
              dealer.setName("Code-Red Dealer");
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
                  var dto = finishedGoodsService.createFinishedGood(req);
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

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
