package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
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
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("reconciliation")
class CR_DispatchBusinessMathFuzzTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private CreditLimitOverrideService creditLimitOverrideService;
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
  void fuzz_dispatchMath_discount_tax_inclusiveExclusive_rounding_invariantsHold() {
    long seed = 0x5EEDC0DEL;
    SplittableRandom random = new SplittableRandom(seed);

    String companyCode = "CR-FUZZ-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));

    FinishedGood fg = ensureFinishedGoodWithCatalog(company, accounts, "FG-FUZZ-" + shortId());
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-FUZZ",
            new BigDecimal("100000"),
            new BigDecimal("1.00"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();

    List<BigDecimal> gstRates =
        List.of(
            BigDecimal.ZERO,
            new BigDecimal("0.01"),
            new BigDecimal("5.00"),
            new BigDecimal("12.00"),
            new BigDecimal("18.00"),
            new BigDecimal("28.00"));

    for (int i = 0; i < 25; i++) {
      boolean perItem = random.nextBoolean();
      boolean gstInclusive = random.nextBoolean();

      BigDecimal qty =
          BigDecimal.valueOf(random.nextInt(1, 5001))
              .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
      BigDecimal unitPrice =
          BigDecimal.valueOf(random.nextInt(1, 500_001))
              .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

      BigDecimal gstRate = gstRates.get(random.nextInt(gstRates.size()));
      String gstTreatment = perItem ? "PER_ITEM" : "ORDER_TOTAL";

      if (!perItem && gstRate.compareTo(BigDecimal.ZERO) <= 0) {
        gstRate = new BigDecimal("5.00");
      }
      BigDecimal totalAmount = null; // let the service compute totals for fuzz coverage

      CompanyContextHolder.setCompanyCode(companyCode);
      var orderDto =
          salesService.createOrder(
              new SalesOrderRequest(
                  dealer.getId(),
                  totalAmount,
                  "INR",
                  "CODE-RED fuzz seed=" + seed + " i=" + i,
                  List.of(
                      new SalesOrderItemRequest(
                          fg.getProductCode(),
                          "Fuzz item",
                          qty,
                          unitPrice,
                          perItem ? gstRate : null)),
                  gstTreatment,
                  perItem ? null : gstRate,
                  gstInclusive,
                  UUID.randomUUID().toString()));
      SalesOrder order = salesOrderRepository.findById(orderDto.id()).orElseThrow();
      finishedGoodsService.reserveForOrder(order);
      PackagingSlip slip =
          packagingSlipRepository
              .findByCompanyAndSalesOrderId(company, order.getId())
              .orElseThrow();

      BigDecimal gross = MoneyUtils.roundCurrency(qty.multiply(unitPrice));
      BigDecimal discount =
          random.nextBoolean()
              ? MoneyUtils.roundCurrency(
                  BigDecimal.valueOf(random.nextInt(0, gross.movePointRight(2).intValue() + 1))
                      .movePointLeft(2))
              : null;
      Long overrideRequestId =
          discount != null
              ? createApprovedDispatchOverride(
                  dealer.getId(), slip.getId(), order.getId(), order.getTotalAmount())
              : null;

      var response =
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
                                  line.getOrderedQuantity() != null
                                      ? line.getOrderedQuantity()
                                      : line.getQuantity(),
                                  null,
                                  discount,
                                  null,
                                  null,
                                  "fuzz"))
                      .toList(),
                  "CODE-RED fuzz dispatch seed=" + seed + " i=" + i,
                  "codered",
                  false,
                  discount != null ? "fuzz-discount" : null,
                  overrideRequestId));
      CompanyContextHolder.clear();

      PackagingSlip refreshedSlip =
          packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
      assertThat(refreshedSlip.getInvoiceId()).isEqualTo(response.finalInvoiceId());
      assertThat(refreshedSlip.getJournalEntryId()).isEqualTo(response.arJournalEntryId());
      assertThat(refreshedSlip.getCogsJournalEntryId()).isNotNull();

      Invoice invoice =
          invoiceRepository.findByCompanyAndId(company, response.finalInvoiceId()).orElseThrow();
      BigDecimal invoiceSubtotal =
          invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO;
      BigDecimal invoiceTax =
          invoice.getTaxTotal() != null ? invoice.getTaxTotal() : BigDecimal.ZERO;
      BigDecimal invoiceTotal =
          invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
      assertThat(
              MoneyUtils.withinTolerance(
                  MoneyUtils.roundCurrency(invoiceSubtotal.add(invoiceTax)),
                  invoiceTotal,
                  new BigDecimal("0.01")))
          .as("Invoice subtotal+tax==total within rounding tolerance (seed=%s i=%s)", seed, i)
          .isTrue();

      CoderedDbAssertions.assertBalancedJournal(
          journalEntryRepository, response.arJournalEntryId());
      CoderedDbAssertions.assertBalancedJournal(
          journalEntryRepository, refreshedSlip.getCogsJournalEntryId());

      JournalEntry arJournal =
          journalEntryRepository.findById(response.arJournalEntryId()).orElseThrow();
      BigDecimal totalDebits =
          arJournal.getLines().stream()
              .map(l -> l.getDebit() == null ? BigDecimal.ZERO : l.getDebit())
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal totalCredits =
          arJournal.getLines().stream()
              .map(l -> l.getCredit() == null ? BigDecimal.ZERO : l.getCredit())
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertThat(totalDebits).isEqualByComparingTo(totalCredits);

      BigDecimal arDebit =
          arJournal.getLines().stream()
              .filter(
                  l ->
                      l.getAccount() != null
                          && l.getAccount().getId() != null
                          && l.getAccount().getId().equals(dealer.getReceivableAccount().getId()))
              .map(l -> l.getDebit() == null ? BigDecimal.ZERO : l.getDebit())
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertThat(MoneyUtils.withinTolerance(arDebit, invoiceTotal, new BigDecimal("0.01")))
          .as("AR debit == invoice total (seed=%s i=%s)", seed, i)
          .isTrue();
    }

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    company.setStateCode("29");
    return companyRepository.save(company);
  }

  private Map<String, Account> ensureCoreAccounts(Company company) {
    Account ar = ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
    Account inv = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
    Account gstIn = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);

    company.setDefaultInventoryAccountId(inv.getId());
    company.setDefaultCogsAccountId(cogs.getId());
    company.setDefaultRevenueAccountId(rev.getId());
    company.setDefaultDiscountAccountId(disc.getId());
    company.setDefaultTaxAccountId(gstOut.getId());
    company.setGstInputTaxAccountId(gstIn.getId());
    company.setGstOutputTaxAccountId(gstOut.getId());
    companyRepository.save(company);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "GST_IN", gstIn);
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
              dealer.setStateCode("29");
              dealer.setReceivableAccount(arAccount);
              return dealerRepository.save(dealer);
            });
  }

  private Long createApprovedDispatchOverride(
      Long dealerId, Long slipId, Long orderId, BigDecimal dispatchAmount) {
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
  }

  private FinishedGood ensureFinishedGoodWithCatalog(
      Company company, Map<String, Account> accounts, String sku) {
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            sku,
            sku,
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("GST_OUT").getId());
    CompanyContextHolder.setCompanyCode(company.getCode());
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  var dto = finishedGoodsService.createFinishedGood(req);
                  return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    ensureCatalogProduct(company, fg);
    CompanyContextHolder.clear();
    return fg;
  }

  private void ensureCatalogProduct(Company company, FinishedGood fg) {
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
              p.setMetadata(new HashMap<>());
              p.setGstRate(BigDecimal.ZERO);
              p.setUnitOfMeasure("UNIT");
              p.setActive(true);
              return productionProductRepository.save(p);
            });
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
