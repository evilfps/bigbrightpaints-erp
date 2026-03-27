package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
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
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("reconciliation")
class CR_SalesDispatchInvoiceAccounting extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void retryConfirmDispatch_isIdempotent_noDuplicateInvoiceOrJournals() {
    String companyCode = "CR-SALES-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-1",
            new BigDecimal("50"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("10"), new BigDecimal("12.34"));
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

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
            false,
            null,
            null);

    var first = salesService.confirmDispatch(request);
    var second = salesService.confirmDispatch(request);

    assertThat(first.finalInvoiceId()).isNotNull();
    assertThat(second.finalInvoiceId()).isEqualTo(first.finalInvoiceId());
    assertThat(second.arJournalEntryId()).isEqualTo(first.arJournalEntryId());

    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo("DISPATCHED");
    assertThat(refreshed.getInvoiceId()).isEqualTo(first.finalInvoiceId());
    assertThat(refreshed.getJournalEntryId()).isEqualTo(first.arJournalEntryId());
    assertThat(refreshed.getCogsJournalEntryId()).isNotNull();

    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, company, slip.getId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshed.getJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshed.getCogsJournalEntryId());
    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());
  }

  @Test
  void partialDispatch_thenBackorderDispatch_invariantsHold() {
    String companyCode = "CR-SALES-BO-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(
            company, accounts, "FG-" + shortId(), new BigDecimal("18.00"));
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-BO",
            new BigDecimal("20"),
            new BigDecimal("9.50"),
            Instant.now(),
            null));

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("10"), new BigDecimal("100.00"));
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

    BigDecimal orderedQty =
        slip.getLines().getFirst().getOrderedQuantity() != null
            ? slip.getLines().getFirst().getOrderedQuantity()
            : slip.getLines().getFirst().getQuantity();
    BigDecimal firstShipQty = orderedQty.subtract(new BigDecimal("4"));

    var firstDispatch =
        salesService.confirmDispatch(
            new DispatchConfirmRequest(
                slip.getId(),
                order.getId(),
                slip.getLines().stream()
                    .map(
                        line ->
                            new DispatchConfirmRequest.DispatchLine(
                                line.getId(),
                                null,
                                firstShipQty,
                                null,
                                null,
                                null,
                                null,
                                "partial"))
                    .toList(),
                "partial dispatch",
                "codered",
                false,
                null,
                null));

    List<PackagingSlip> slips =
        packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
    PackagingSlip backorderSlip =
        slips.stream()
            .filter(s -> "BACKORDER".equalsIgnoreCase(s.getStatus()))
            .findFirst()
            .orElseThrow();
    PackagingSlip backorderSlipFull =
        packagingSlipRepository.findByIdAndCompany(backorderSlip.getId(), company).orElseThrow();

    assertThat(firstDispatch.finalInvoiceId()).isNotNull();
    assertThat(backorderSlip.getId()).isNotEqualTo(slip.getId());

    var secondDispatch =
        salesService.confirmDispatch(
            new DispatchConfirmRequest(
                backorderSlipFull.getId(),
                order.getId(),
                backorderSlipFull.getLines().stream()
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
                                "ship remainder"))
                    .toList(),
                "backorder dispatch",
                "codered",
                false,
                null,
                null));

    PackagingSlip refreshedOriginal =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    PackagingSlip refreshedBackorder =
        packagingSlipRepository.findByIdAndCompany(backorderSlip.getId(), company).orElseThrow();
    assertThat(refreshedOriginal.getStatus()).isEqualTo("DISPATCHED");
    assertThat(refreshedBackorder.getStatus()).isEqualTo("DISPATCHED");

    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, company, slip.getId());
    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, company, backorderSlip.getId());

    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshedOriginal.getJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshedOriginal.getCogsJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshedBackorder.getJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshedBackorder.getCogsJournalEntryId());

    FinishedGood refreshed = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    assertThat(refreshed.getCurrentStock())
        .as("FG stock never negative")
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(refreshed.getReservedStock())
        .as("FG reserved never negative")
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());

    Invoice firstInvoice =
        invoiceRepository.findByCompanyAndId(company, firstDispatch.finalInvoiceId()).orElseThrow();
    Invoice secondInvoice =
        invoiceRepository
            .findByCompanyAndId(company, secondDispatch.finalInvoiceId())
            .orElseThrow();
    assertThat(firstInvoice.getId()).isNotEqualTo(secondInvoice.getId());
  }

  @Test
  void concurrentConfirmDispatch_withRetries_producesSingleDurableOutcome() {
    String companyCode = "CR-SALES-CONC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-CONC",
            new BigDecimal("50"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));

    SalesOrder order =
        createOrder(
            company, dealer, fg.getProductCode(), new BigDecimal("10"), new BigDecimal("99.99"));
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

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
            "CODE-RED concurrent dispatch",
            "codered",
            false,
            null,
            null);

    CoderedConcurrencyHarness.RunResult<
            com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse>
        result =
            CoderedConcurrencyHarness.run(
                8,
                5,
                Duration.ofSeconds(45),
                threadIndex ->
                    () -> {
                      CompanyContextHolder.setCompanyCode(companyCode);
                      try {
                        return salesService.confirmDispatch(request);
                      } finally {
                        CompanyContextHolder.clear();
                      }
                    },
                CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .as("No concurrency callers should fail")
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> invoiceIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<
                                com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse>)
                            outcome)
                        .value())
            .map(com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse::finalInvoiceId)
            .distinct()
            .toList();
    assertThat(invoiceIds).as("All callers should converge on one invoice").hasSize(1);

    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo("DISPATCHED");

    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, company, slip.getId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshed.getJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, refreshed.getCogsJournalEntryId());
    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());
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
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
    Account gstIn = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);

    updateCompanyDefaults(company, inv, cogs, rev, disc, gstOut, gstIn);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "GST_IN", gstIn);
  }

  private void updateCompanyDefaults(
      Company company,
      Account inv,
      Account cogs,
      Account rev,
      Account disc,
      Account gstOut,
      Account gstIn) {
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
      if (fresh.getGstInputTaxAccountId() == null) {
        fresh.setGstInputTaxAccountId(gstIn.getId());
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
                  var dto = finishedGoodsService.createFinishedGood(req);
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
    return salesOrderRepository.findById(orderDto.id()).orElseThrow();
  }

  private PackagingSlip reserveSlip(Company company, SalesOrder order) {
    var reservation = finishedGoodsService.reserveForOrder(order);
    if (reservation != null
        && reservation.packagingSlip() != null
        && reservation.packagingSlip().id() != null) {
      return packagingSlipRepository
          .findByIdAndCompany(reservation.packagingSlip().id(), company)
          .orElseThrow();
    }
    return packagingSlipRepository
        .findByCompanyAndSalesOrderId(company, order.getId())
        .orElseThrow();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
