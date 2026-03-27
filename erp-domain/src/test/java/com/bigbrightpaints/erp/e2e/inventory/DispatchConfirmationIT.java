package com.bigbrightpaints.erp.e2e.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
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

/**
 * Covers the dispatch confirmation flow: order -> reserve -> confirm dispatch,
 * ensuring stock decrements and slip moves to DISPATCHED with no backorders.
 */
@TestPropertySource(properties = "erp.auto-approval.enabled=false")
class DispatchConfirmationIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "DISPATCH-E2E";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private SalesService salesService;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;

  private Company company;
  private Dealer dealer;
  private Map<String, Account> accounts;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        "dispatch@bbp.com",
        "dispatch123",
        "Dispatch User",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY"));
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setBaseCurrency("INR");
    companyRepository.save(company);

    accounts = ensureAccounts(company);
    dealer = ensureDealer(company, "DISPATCH-DEALER", "Dispatch Dealer", accounts.get("AR"));
  }

  @Test
  @Transactional
  void confirmDispatch_consumesStock_andSetsSlipToDispatched() {
    // Arrange: finished good + stock + catalog entry
    String sku = "FG-DISP-" + UUID.randomUUID().toString().substring(0, 6);
    FinishedGood fg = createFinishedGood(sku);
    ensureCatalogProduct(fg);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-DISP",
            new BigDecimal("50"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));

    // Sales order for 10 units
    SalesOrderRequest orderReq =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("100.00"),
            "INR",
            null,
            List.of(
                new SalesOrderItemRequest(
                    fg.getProductCode(),
                    "Dispatch Item",
                    new BigDecimal("10"),
                    new BigDecimal("10.00"),
                    BigDecimal.ZERO)),
            "EXCLUSIVE",
            null,
            null,
            null);
    var orderDto = salesService.createOrder(orderReq);
    SalesOrder order = salesOrderRepository.findById(orderDto.id()).orElseThrow();

    // Reserve builds slip with lines
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

    // Build confirmation request with shipped = ordered per line
    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            slip.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmationRequest.LineConfirmation(
                            line.getId(),
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            "ship all"))
                .toList(),
            "Final check done",
            "dispatch-user",
            null);

    var response = finishedGoodsService.confirmDispatch(request, "dispatch-user");

    // Assert: slip dispatched, stock reduced by 10, backorder zero
    assertThat(response.status()).isEqualTo("DISPATCHED");
    assertThat(response.totalBackorderAmount()).isZero();
    FinishedGood refreshed = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(new BigDecimal("40.00"));
    assertThat(refreshed.getReservedStock()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(packagingSlipRepository.findById(slip.getId()).orElseThrow().getStatus())
        .isEqualTo("DISPATCHED");
    assertThat(response.totalShippedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
    // line shipped equals ordered
    response
        .lines()
        .forEach(l -> assertThat(l.shippedQuantity()).isEqualByComparingTo(l.orderedQuantity()));
  }

  @Test
  @Transactional
  void confirmBackorderSlip_usesProvidedSlipId_andShipsRemaining() {
    String sku = "FG-BO-" + UUID.randomUUID().toString().substring(0, 6);
    FinishedGood fg = createFinishedGood(sku);
    ensureCatalogProduct(fg);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-BO",
            new BigDecimal("10"),
            new BigDecimal("5.00"),
            Instant.now(),
            null));

    SalesOrderRequest orderReq =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("100.00"),
            "INR",
            null,
            List.of(
                new SalesOrderItemRequest(
                    fg.getProductCode(),
                    "Backorder Item",
                    new BigDecimal("10"),
                    new BigDecimal("10.00"),
                    BigDecimal.ZERO)),
            "EXCLUSIVE",
            null,
            null,
            null);
    var orderDto = salesService.createOrder(orderReq);
    SalesOrder order = salesOrderRepository.findById(orderDto.id()).orElseThrow();

    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

    PackagingSlipLine firstLine = slip.getLines().getFirst();
    BigDecimal orderedQty = firstLine.getOrderedQuantity();
    assertThat(orderedQty).isGreaterThan(new BigDecimal("4"));
    BigDecimal firstShipmentQty = orderedQty.subtract(new BigDecimal("4"));

    // Ship only part of the ordered qty to force a backorder slip
    DispatchConfirmationRequest firstConfirm =
        new DispatchConfirmationRequest(
            slip.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmationRequest.LineConfirmation(
                            line.getId(), firstShipmentQty, "partial ship"))
                .toList(),
            "Partial dispatch",
            "dispatch-user",
            null);
    var firstResponse = finishedGoodsService.confirmDispatch(firstConfirm, "dispatch-user");
    assertThat(firstResponse.backorderSlipId()).isNotNull();

    PackagingSlip backorderSlip =
        packagingSlipRepository.findById(firstResponse.backorderSlipId()).orElseThrow();
    assertThat(backorderSlip.getStatus()).isEqualTo("BACKORDER");

    // Now ship the remaining backorder slip explicitly by its ID
    DispatchConfirmationRequest secondConfirm =
        new DispatchConfirmationRequest(
            backorderSlip.getId(),
            backorderSlip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmationRequest.LineConfirmation(
                            line.getId(), line.getOrderedQuantity(), "ship remainder"))
                .toList(),
            "Backorder cleared",
            "dispatch-user",
            null);
    var secondResponse = finishedGoodsService.confirmDispatch(secondConfirm, "dispatch-user");

    assertThat(secondResponse.status()).isEqualTo("DISPATCHED");
    assertThat(secondResponse.totalBackorderAmount()).isZero();

    PackagingSlip refreshedOriginal = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    PackagingSlip refreshedBackorder =
        packagingSlipRepository.findById(backorderSlip.getId()).orElseThrow();
    assertThat(refreshedOriginal.getStatus()).isEqualTo("DISPATCHED");
    assertThat(refreshedBackorder.getStatus()).isEqualTo("DISPATCHED");

    FinishedGood refreshed = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(refreshed.getReservedStock()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // Helpers
  private Map<String, Account> ensureAccounts(Company company) {
    return Map.of(
        "AR", ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET),
        "INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET),
        "COGS", ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.EXPENSE),
        "REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE),
        "DISC", ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE),
        "TAX", ensureAccount(company, "TAX", "Tax Payable", AccountType.LIABILITY));
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

  private Dealer ensureDealer(Company company, String code, String name, Account arAccount) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Dealer d = new Dealer();
              d.setCompany(company);
              d.setCode(code);
              d.setName(name);
              d.setStatus("ACTIVE");
              d.setReceivableAccount(arAccount);
              return dealerRepository.save(d);
            });
  }

  private FinishedGood createFinishedGood(String productCode) {
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            productCode,
            productCode + " Name",
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("TAX").getId());
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, productCode)
        .orElseGet(
            () -> {
              var dto = finishedGoodsService.createFinishedGood(req);
              return finishedGoodRepository.findById(dto.id()).orElseThrow();
            });
  }

  private void ensureCatalogProduct(FinishedGood fg) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(fg.getCompany(), "DISP-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(fg.getCompany());
                  b.setCode("DISP-BRAND");
                  b.setName("Dispatch Brand");
                  return productionBrandRepository.save(b);
                });
    productionProductRepository
        .findByCompanyAndSkuCode(fg.getCompany(), fg.getProductCode())
        .orElseGet(
            () -> {
              ProductionProduct p = new ProductionProduct();
              p.setCompany(fg.getCompany());
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
              p.setGstRate(BigDecimal.ZERO);
              p.setUnitOfMeasure("UNIT");
              p.setActive(true);
              return productionProductRepository.save(p);
            });
  }
}
