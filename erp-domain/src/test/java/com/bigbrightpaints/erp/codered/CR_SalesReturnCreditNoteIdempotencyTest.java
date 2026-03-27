package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class CR_SalesReturnCreditNoteIdempotencyTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private CreditLimitOverrideService creditLimitOverrideService;
  @Autowired private SalesReturnService salesReturnService;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private AccountingService accountingService;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void salesReturn_isIdempotent_underConcurrency_andAvoidsDuplicateMovements() {
    String companyCode = "CR-RET-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-RET-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-RET-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();
    Long overrideRequestId =
        createApprovedDispatchOverride(
            companyCode, dealer.getId(), slip.getId(), order.getId(), order.getTotalAmount());

    DispatchConfirmRequest request =
        new DispatchConfirmRequest(
            slip.getId(),
            order.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmRequest.DispatchLine(
                            line.getId(),
                            null,
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            null,
                            null,
                            null,
                            null,
                            null))
                .toList(),
            "CODE-RED dispatch",
            "codered",
            true,
            "discount override for credit-note idempotency regression",
            overrideRequestId);

    CompanyContextHolder.setCompanyCode(companyCode);
    var dispatch = salesService.confirmDispatch(request);
    CompanyContextHolder.clear();

    Invoice invoice =
        invoiceRepository.findByCompanyAndId(company, dispatch.finalInvoiceId()).orElseThrow();
    InvoiceLine line = invoice.getLines().getFirst();
    SalesReturnRequest returnRequest =
        new SalesReturnRequest(
            invoice.getId(),
            "CODE-RED return",
            List.of(new SalesReturnRequest.ReturnLine(line.getId(), new BigDecimal("1"))));

    CoderedConcurrencyHarness.RunResult<JournalEntryDto> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return salesReturnService.processReturn(returnRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("return calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
            .map(JournalEntryDto::id)
            .distinct()
            .toList();
    assertThat(journalIds).as("single return journal").hasSize(1);
    Long journalId = journalIds.getFirst();
    assertThat(journalEntryRepository.findById(journalId))
        .as("return journal persisted")
        .isPresent()
        .get()
        .satisfies(entry -> assertThat(entry.getReferenceNumber()).startsWith("CRN-"));

    List<InventoryMovement> returnMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                company, "SALES_RETURN", invoice.getInvoiceNumber());
    assertThat(returnMovements).as("single return movement").hasSize(1);
  }

  @Test
  void creditNote_isIdempotent_underConcurrency_andRejectsMismatch() {
    String companyCode = "CR-CN-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-CN-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-CN-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();
    Long overrideRequestId =
        createApprovedDispatchOverride(
            companyCode, dealer.getId(), slip.getId(), order.getId(), order.getTotalAmount());

    DispatchConfirmRequest request =
        new DispatchConfirmRequest(
            slip.getId(),
            order.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmRequest.DispatchLine(
                            line.getId(),
                            null,
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            null,
                            null,
                            null,
                            null,
                            null))
                .toList(),
            "CODE-RED dispatch",
            "codered",
            true,
            "discount override for credit-note idempotency regression",
            overrideRequestId);

    CompanyContextHolder.setCompanyCode(companyCode);
    var dispatch = salesService.confirmDispatch(request);
    CompanyContextHolder.clear();

    Invoice invoice =
        invoiceRepository.findByCompanyAndId(company, dispatch.finalInvoiceId()).orElseThrow();
    BigDecimal startingOutstanding = invoice.getOutstandingAmount();
    BigDecimal creditAmount =
        startingOutstanding.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    String idempotencyKey = "CN-IDEMP-" + UUID.randomUUID();

    CreditNoteRequest creditRequest =
        new CreditNoteRequest(
            invoice.getId(),
            creditAmount,
            null,
            null,
            "CODE-RED credit note",
            idempotencyKey,
            Boolean.FALSE);

    CoderedConcurrencyHarness.RunResult<JournalEntryDto> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.postCreditNote(creditRequest);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("credit note calls succeed")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
            .map(JournalEntryDto::id)
            .distinct()
            .toList();
    assertThat(journalIds).as("single credit note journal").hasSize(1);
    Long journalId = journalIds.getFirst();

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount())
        .as("outstanding reduced once")
        .isEqualByComparingTo(startingOutstanding.subtract(creditAmount));

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto retry = accountingService.postCreditNote(creditRequest);
      assertThat(retry.id()).as("retry returns same credit note").isEqualTo(journalId);
      CreditNoteRequest conflictRequest =
          new CreditNoteRequest(
              invoice.getId(),
              creditAmount.subtract(new BigDecimal("1.00")),
              null,
              null,
              "CODE-RED credit note",
              idempotencyKey,
              Boolean.FALSE);
      assertThatThrownBy(() -> accountingService.postCreditNote(conflictRequest))
          .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
          .satisfies(
              error ->
                  assertThat(
                          ((com.bigbrightpaints.erp.core.exception.ApplicationException) error)
                              .getErrorCode())
                      .isEqualTo(
                          com.bigbrightpaints.erp.core.exception.ErrorCode.CONCURRENCY_CONFLICT));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void creditNote_discountedInvoice_idempotentRetry_returnsSameJournal() {
    String companyCode = "CR-CN-DISC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(
            company, accounts, "FG-CN-DISC-" + shortId(), BigDecimal.ZERO);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-CN-DISC-1",
            new BigDecimal("20"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("5"), new BigDecimal("15.50"));
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();
    Long overrideRequestId =
        createApprovedDispatchOverride(
            companyCode, dealer.getId(), slip.getId(), order.getId(), order.getTotalAmount());

    DispatchConfirmRequest request =
        new DispatchConfirmRequest(
            slip.getId(),
            order.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmRequest.DispatchLine(
                            line.getId(),
                            null,
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            null,
                            new BigDecimal("5.00"),
                            null,
                            null,
                            null))
                .toList(),
            "CODE-RED dispatch",
            "codered",
            true,
            "discount override for credit-note idempotency regression",
            overrideRequestId);

    CompanyContextHolder.setCompanyCode(companyCode);
    var dispatch = salesService.confirmDispatch(request);
    CompanyContextHolder.clear();

    Invoice invoice =
        invoiceRepository.findByCompanyAndId(company, dispatch.finalInvoiceId()).orElseThrow();
    BigDecimal startingOutstanding = invoice.getOutstandingAmount();
    BigDecimal grossWithoutDiscount =
        new BigDecimal("15.50").multiply(new BigDecimal("5")).setScale(2, RoundingMode.HALF_UP);
    assertThat(startingOutstanding)
        .isEqualByComparingTo(grossWithoutDiscount.subtract(new BigDecimal("5.00")));
    BigDecimal creditAmount =
        startingOutstanding.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    String idempotencyKey = "CN-DISC-IDEMP-" + UUID.randomUUID();
    CreditNoteRequest creditRequest =
        new CreditNoteRequest(
            invoice.getId(),
            creditAmount,
            null,
            null,
            "CODE-RED credit note",
            idempotencyKey,
            Boolean.FALSE);

    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      JournalEntryDto first = accountingService.postCreditNote(creditRequest);
      JournalEntryDto retry = accountingService.postCreditNote(creditRequest);
      assertThat(retry.id()).isEqualTo(first.id());
    } finally {
      CompanyContextHolder.clear();
    }

    Invoice refreshed =
        invoiceRepository.findByCompanyAndId(company, invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount())
        .isEqualByComparingTo(startingOutstanding.subtract(creditAmount));
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

    updateCompanyDefaults(company, inv, cogs, rev, disc, gstOut);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut);
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
    BigDecimal totalAmount = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
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
    SalesOrder order = salesOrderRepository.findById(orderDto.id()).orElseThrow();
    finishedGoodsService.reserveForOrder(order);
    CompanyContextHolder.clear();
    return order;
  }

  private Long createApprovedDispatchOverride(
      String companyCode, Long dealerId, Long slipId, Long orderId, BigDecimal dispatchAmount) {
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      var created =
          creditLimitOverrideService.createRequest(
              new CreditLimitOverrideRequestCreateRequest(
                  dealerId,
                  slipId,
                  orderId,
                  dispatchAmount,
                  "CODE-RED dispatch exception approval",
                  Instant.now().plus(Duration.ofHours(2))),
              "codered-maker");
      var approved =
          creditLimitOverrideService.approveRequest(
              created.id(),
              new CreditLimitOverrideDecisionRequest(
                  "CODE-RED dispatch exception approved", Instant.now().plus(Duration.ofHours(4))),
              "codered-checker");
      return approved.id();
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
