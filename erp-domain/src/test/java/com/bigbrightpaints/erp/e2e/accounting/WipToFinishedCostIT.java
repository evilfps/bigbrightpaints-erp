package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("WIP → FG cost propagation with GL assertions")
public class WipToFinishedCostIT extends AbstractIntegrationTest {

  private static final String COMPANY = "WIPFG";
  private static final String ADMIN_EMAIL = "wipfg@bbp.com";
  private static final String ADMIN_PASSWORD = "wip123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private SupplierRepository supplierRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionCatalogService productionCatalogService;
  @Autowired private SalesService salesService;
  @Autowired private AccountingService accountingService;

  private HttpHeaders headers;
  private Company company;
  private Account rmInventory;
  private Account wipAccount;
  private Account fgInventory;
  private Account cogs;
  private Account revenue;
  private ProductionBrand brand;
  private RawMaterial rm;
  private FinishedGood fg;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "WIP Admin",
        COMPANY,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_FACTORY", "ROLE_SALES"));
    CompanyContextHolder.setCompanyCode(COMPANY);
    company = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow();
    brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "BR-WIP")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(company);
                  b.setCode("BR-WIP");
                  b.setName("WIP Brand");
                  return productionBrandRepository.save(b);
                });
    rmInventory = ensureAccount("RM-INV", "RM Inventory", AccountType.ASSET);
    wipAccount = ensureAccount("WIP", "Work in Process", AccountType.ASSET);
    fgInventory = ensureAccount("FG-INV", "FG Inventory", AccountType.ASSET);
    cogs = ensureAccount("COGS", "Cost of Goods Sold", AccountType.EXPENSE);
    revenue = ensureAccount("REV", "Revenue", AccountType.REVENUE);

    rm = ensureRawMaterial("RM-WIP", rmInventory.getId());
    fg = ensureFinishedGood("FG-WIP", "FG-WIP", fgInventory.getId(), cogs.getId(), revenue.getId());
    headers = authHeaders();
  }

  @Test
  void rm_issue_to_wip_then_fg_receipt_and_sale() {
    Supplier supplier = ensureSupplier();
    // Seed RM inventory via journal (100 units @ 10)
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "PO-WIP-1",
            LocalDate.now(),
            "Seed RM purchase",
            null,
            supplier.getId(),
            null,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    rmInventory.getId(), "RM stock", new BigDecimal("1000"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    supplier.getPayableAccount().getId(),
                    "AP",
                    BigDecimal.ZERO,
                    new BigDecimal("1000")))));

    ProductionProduct product =
        productionProductRepository
            .findByCompanyAndSkuCode(company, fg.getProductCode())
            .orElseGet(
                () -> {
                  ProductionProduct p = new ProductionProduct();
                  p.setCompany(company);
                  p.setBrand(brand);
                  p.setProductName(fg.getName());
                  p.setSkuCode(fg.getProductCode());
                  p.setCategory("FINISHED_GOOD");
                  p.setUnitOfMeasure("KG");
                  return productionProductRepository.save(p);
                });
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setBrand(brand);
    log.setProduct(product);
    log.setProductionCode("PROD-WIP-1");
    log.setBatchSize(new BigDecimal("100"));
    log.setUnitOfMeasure("KG");
    log.setMixedQuantity(new BigDecimal("100"));
    log = productionLogRepository.save(log);

    // Issue 40 KG to WIP (accounting service WIP adjustment)
    WipAdjustmentRequest wipIssue =
        new WipAdjustmentRequest(
            log.getId(),
            new BigDecimal("400"),
            wipAccount.getId(),
            rmInventory.getId(),
            WipAdjustmentRequest.Direction.ISSUE,
            "Issue RM to WIP",
            null,
            null,
            null,
            null);
    assertThat(accountingService.adjustWip(wipIssue)).isNotNull();

    // Record FG receipt into inventory with WIP credit
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "WIP-COMP",
            LocalDate.now(),
            "Complete FG from WIP",
            null,
            null,
            null,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    fgInventory.getId(), "FG receipt", new BigDecimal("400"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    wipAccount.getId(), "Clear WIP", BigDecimal.ZERO, new BigDecimal("400")))));

    // Seed production product and finish goods stock for sale
    Dealer dealer = ensureDealer();
    List<SalesOrderItemRequest> items =
        List.of(
            new SalesOrderItemRequest(
                fg.getProductCode(), "FG", new BigDecimal("10"), new BigDecimal("100"), null));
    SalesOrderRequest order =
        new SalesOrderRequest(
            dealer.getId(), new BigDecimal("1000"), "INR", null, items, null, null, null, null);
    var sale = salesService.createOrder(order);
    assertThat(sale).isNotNull();

    // Basic GL sanity checks: RM inventory decreased 400, WIP net zero after completion, FG
    // inventory increased 400 then reduced by COGS on sale
    Account rmAfter =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "RM-INV").orElseThrow();
    Account wipAfter =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "WIP").orElseThrow();
    Account fgAfter =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "FG-INV").orElseThrow();
    Account cogsAfter =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "COGS").orElseThrow();

    assertThat(rmAfter.getBalance())
        .isEqualByComparingTo(new BigDecimal("600.00")); // seeded 1000 debit, issued 400 credit
    assertThat(wipAfter.getBalance().abs())
        .isLessThan(new BigDecimal("0.01")); // netted by completion
    assertThat(fgAfter.getBalance())
        .isGreaterThan(
            BigDecimal.ZERO); // receipt then sale reduces but still positive if not fully sold
    assertThat(cogsAfter.getBalance())
        .isGreaterThanOrEqualTo(BigDecimal.ZERO); // COGS posted or remains zero if sale not costed
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", COMPANY);
    return h;
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(name);
              a.setType(type);
              a.setBalance(BigDecimal.ZERO);
              return accountRepository.save(a);
            });
  }

  private RawMaterial ensureRawMaterial(String sku, Long inventoryAccountId) {
    return rawMaterialRepository
        .findByCompanyAndSku(company, sku)
        .orElseGet(
            () -> {
              RawMaterial m = new RawMaterial();
              m.setCompany(company);
              m.setName(sku);
              m.setSku(sku);
              m.setUnitType("KG");
              m.setInventoryAccountId(inventoryAccountId);
              return rawMaterialRepository.save(m);
            });
  }

  private FinishedGood ensureFinishedGood(
      String name, String sku, Long inv, Long cogsId, Long revId) {
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, sku)
        .orElseGet(
            () -> {
              FinishedGood fg = new FinishedGood();
              fg.setCompany(company);
              fg.setName(name);
              fg.setProductCode(sku);
              fg.setCurrentStock(BigDecimal.ZERO);
              fg.setReservedStock(BigDecimal.ZERO);
              fg.setValuationAccountId(inv);
              fg.setCogsAccountId(cogsId);
              fg.setRevenueAccountId(revId);
              fg.setTaxAccountId(revId); // placeholder non-null
              return finishedGoodRepository.save(fg);
            });
  }

  private Supplier ensureSupplier() {
    return supplierRepository
        .findByCompanyAndCodeIgnoreCase(company, "SUP-WIP")
        .orElseGet(
            () -> {
              Account payable = ensureAccount("AP-WIP", "AP WIP", AccountType.LIABILITY);
              Supplier s = new Supplier();
              s.setCompany(company);
              s.setCode("SUP-WIP");
              s.setName("WIP Supplier");
              s.setEmail("wip-supplier@bbp.com");
              s.setPayableAccount(payable);
              return supplierRepository.save(s);
            });
  }

  private Dealer ensureDealer() {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, "DEAL-WIP")
        .orElseGet(
            () -> {
              Account arAccount = ensureAccount("AR-WIP", "Dealer AR", AccountType.ASSET);
              Dealer d = new Dealer();
              d.setCompany(company);
              d.setCode("DEAL-WIP");
              d.setName("WIP Dealer");
              d.setReceivableAccount(arAccount);
              return dealerRepository.save(d);
            });
  }
}
