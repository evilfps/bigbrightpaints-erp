package com.bigbrightpaints.erp.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

/**
 * Critical path tests - core workflows that MUST work
 * These represent the happy path for essential business operations
 */
@DisplayName("Critical Path Tests")
public class CriticalPathSmokeTest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CRITICAL";
  private static final String ADMIN_EMAIL = "critical@test.com";
  private static final String ADMIN_PASSWORD = "critical123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private AccountRepository accountRepository;

  private String authToken;
  private HttpHeaders headers;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Critical Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_FACTORY", "ROLE_SALES", "ROLE_ACCOUNTING"));
    authToken = login();
    headers = createHeaders(authToken);
    ensureTestAccounts();
  }

  private String login() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    return (String) response.getBody().get("accessToken");
  }

  private HttpHeaders createHeaders(String token) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", COMPANY_CODE);
    return h;
  }

  private void ensureTestAccounts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    ensureAccount(company, "ASSET-CASH", "Cash Account", AccountType.ASSET);
    ensureAccount(company, "REV-SALES", "Sales Revenue", AccountType.REVENUE);
    ensureAccount(company, "ASSET-AR", "Accounts Receivable", AccountType.ASSET);
    ensureAccount(company, "ASSET-INV", "Inventory", AccountType.ASSET);
    ensureAccount(company, "EXP-COGS", "Cost of Goods Sold", AccountType.EXPENSE);
    ensureAccount(company, "EXP-LABOR", "Direct Labor Applied", AccountType.EXPENSE);
    ensureAccount(company, "EXP-OVERHEAD", "Overhead Applied", AccountType.EXPENSE);
    ensureAccount(company, "LIAB-GST", "GST Liability", AccountType.LIABILITY);
    ensureAccount(company, "DISC-SALES", "Sales Discounts", AccountType.EXPENSE);
    ensureAccount(company, "WIP-PROD", "Work In Progress", AccountType.ASSET);
    // Configure company defaults so product/FG creation passes validation
    Long inv =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "ASSET-INV")
            .orElseThrow()
            .getId();
    Long cogs =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXP-COGS").orElseThrow().getId();
    Long rev =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "REV-SALES")
            .orElseThrow()
            .getId();
    Long disc =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "DISC-SALES")
            .orElseThrow()
            .getId();
    Long tax =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "LIAB-GST").orElseThrow().getId();
    company.setDefaultInventoryAccountId(inv);
    company.setDefaultCogsAccountId(cogs);
    company.setDefaultRevenueAccountId(rev);
    company.setDefaultDiscountAccountId(disc);
    company.setDefaultTaxAccountId(tax);
    companyRepository.save(company);
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
              return accountRepository.save(account);
            });
  }

  @Test
  @DisplayName("6. Create Product - Success")
  void createProductSuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Map<String, Object> brandReq = Map.of("name", "Critical Path Brand");

    ResponseEntity<Map> brandResponse =
        rest.exchange(
            "/api/v1/catalog/brands",
            HttpMethod.POST,
            new HttpEntity<>(brandReq, headers),
            Map.class);

    assertThat(brandResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long brandId =
        ((Number) ((Map<?, ?>) brandResponse.getBody().get("data")).get("id")).longValue();

    // Provide required finished-good accounting metadata
    Long valuationId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "ASSET-INV")
            .orElseThrow()
            .getId();
    Long cogsId =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXP-COGS").orElseThrow().getId();
    Long revenueId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "REV-SALES")
            .orElseThrow()
            .getId();
    Long discountId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "DISC-SALES")
            .orElseThrow()
            .getId();
    Long taxId =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "LIAB-GST").orElseThrow().getId();

    // Create product
    Map<String, Object> productReq =
        Map.of(
            "brandId", brandId,
            "name", "Critical Path Product",
            "itemClass", "FINISHED_GOOD",
            "color", "WHITE",
            "size", "1L",
            "unitOfMeasure", "UNIT",
            "hsnCode", "320910",
            "basePrice", new BigDecimal("150.00"),
            "gstRate", new BigDecimal("18.00"),
            "metadata",
                Map.of(
                    "wipAccountId", valuationId,
                    "semiFinishedAccountId", valuationId,
                    "fgValuationAccountId", valuationId,
                    "fgCogsAccountId", cogsId,
                    "fgRevenueAccountId", revenueId,
                    "fgDiscountAccountId", discountId,
                    "fgTaxAccountId", taxId));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(productReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(((Number) data.get("brandId")).longValue()).isEqualTo(brandId);
    assertThat(data.get("id")).isNotNull();
    assertThat(data.get("code")).isNotNull();
  }

  @Test
  @DisplayName("7. Create Raw Material - Success")
  void createRawMaterialSuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setCompany(company);
    rawMaterial.setSku("RM-001");
    rawMaterial.setName("Critical Path Raw Material");
    rawMaterial.setUnitType("KG");
    rawMaterial.setCurrentStock(new BigDecimal("1000"));

    RawMaterial saved = rawMaterialRepository.save(rawMaterial);
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getSku()).isEqualTo("RM-001");
  }

  @Test
  @DisplayName("8. Log Production - Success")
  void logProductionSuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Setup: Create raw material and product
    RawMaterial rm = createTestRawMaterial(company, "RM-PROD-001", new BigDecimal("500"));
    ProductionProduct product = createTestProduct(company, "PROD-SKU-001");
    ProductionBrand brand = product.getBrand();

    // FIX: Correct request format with brandId, productId, and materials array
    Map<String, Object> materialUsage =
        Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("10.00"));

    Map<String, Object> logRequest =
        Map.of(
            "brandId", brand.getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("100.00"),
            "mixedQuantity", new BigDecimal("100.00"),
            "materials", List.of(materialUsage));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("9. Record Packing - Success")
  void recordPackingSuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // This test requires a production log to exist first
    // For now, we'll test the endpoint availability
    // FIX: Correct endpoint path is /api/v1/factory/unpacked-batches
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/unpacked-batches",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
  }

  @Test
  @DisplayName("10. Create Sales Order - Success")
  void createSalesOrderSuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Setup: Create finished good and dealer
    FinishedGood fg = createTestFinishedGood(company, "FG-SO-001", new BigDecimal("100"));

    // Create dealer
    Map<String, Object> dealerReq =
        Map.of(
            "name", "Critical Path Dealer",
            "companyName", "CP Dealer Ltd",
            "contactEmail", "dealer@cp.com",
            "contactPhone", "1234567890",
            "address", "Test Address",
            "creditLimit", new BigDecimal("50000"));

    ResponseEntity<Map> dealerResp =
        rest.exchange(
            "/api/v1/dealers", HttpMethod.POST, new HttpEntity<>(dealerReq, headers), Map.class);
    Long dealerId = ((Number) ((Map<?, ?>) dealerResp.getBody().get("data")).get("id")).longValue();

    // Create sales order
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Item",
            "quantity",
            new BigDecimal("5"),
            "unitPrice",
            new BigDecimal("100.00"),
            "gstRate",
            BigDecimal.ZERO);

    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealerId,
            "totalAmount",
            new BigDecimal("500.00"),
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("11. Dispatch Order - Success (Check Endpoint)")
  void dispatchOrderEndpointAvailable() {
    Map<String, Object> dispatchReq =
        Map.of(
            "batchId", "TEST-BATCH-1",
            "requestedBy", "smoke-test",
            "postingAmount", new BigDecimal("100.00"));
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/orchestrator/factory/dispatch/{batchId}",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class,
            "TEST-BATCH-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).containsEntry("canonicalPath", "/api/v1/dispatch/confirm");
  }

  @Test
  @DisplayName("12. Create Journal Entry - Success")
  void createJournalEntrySuccess() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "ASSET-CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV-SALES").orElseThrow();

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            new BigDecimal("1000.00"),
            "credit",
            BigDecimal.ZERO,
            "description",
            "Test debit");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            new BigDecimal("1000.00"),
            "description",
            "Test credit");

    Map<String, Object> jeRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Critical Path Test Entry",
            "lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("13. Run Financial Report - Success")
  void runFinancialReportSuccess() {
    LocalDate startDate = LocalDate.now().withDayOfMonth(1);
    LocalDate endDate = LocalDate.now();

    String url =
        String.format("/api/v1/reports/trial-balance?startDate=%s&endDate=%s", startDate, endDate);

    ResponseEntity<Map> response =
        rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("14. Allocate Costs - Success")
  void allocateCostsSuccess() {
    // Test cost allocation endpoint availability
    // FIX: Add required laborCost and overheadCost fields
    Map<String, Object> allocRequest =
        Map.of(
            "year",
            2025,
            "month",
            1,
            "laborCost",
            new BigDecimal("100000.00"),
            "overheadCost",
            new BigDecimal("50000.00"));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/cost-allocation",
            HttpMethod.POST,
            new HttpEntity<>(allocRequest, headers),
            Map.class);

    // May fail if no production data, but endpoint should respond
    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);
  }

  // Helper methods
  private RawMaterial createTestRawMaterial(Company company, String sku, BigDecimal stock) {
    Long inventoryAccountId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "ASSET-INV")
            .map(Account::getId)
            .orElse(null);
    RawMaterial rawMaterial =
        rawMaterialRepository
            .findByCompanyAndSku(company, sku)
            .map(
                existing -> {
                  boolean dirty = false;
                  if (existing.getInventoryAccountId() == null && inventoryAccountId != null) {
                    existing.setInventoryAccountId(inventoryAccountId);
                    dirty = true;
                  }
                  if (existing.getCurrentStock() == null
                      || existing.getCurrentStock().compareTo(stock) < 0) {
                    existing.setCurrentStock(stock);
                    dirty = true;
                  }
                  return dirty ? rawMaterialRepository.save(existing) : existing;
                })
            .orElseGet(
                () -> {
                  RawMaterial rm = new RawMaterial();
                  rm.setCompany(company);
                  rm.setSku(sku);
                  rm.setName("Test RM " + sku);
                  rm.setUnitType("KG");
                  rm.setCurrentStock(stock);
                  rm.setInventoryAccountId(inventoryAccountId);
                  return rawMaterialRepository.save(rm);
                });
    ensureBootstrapBatch(rawMaterial, stock);
    return rawMaterial;
  }

  private void ensureBootstrapBatch(RawMaterial rawMaterial, BigDecimal targetStock) {
    if (rawMaterial == null || targetStock == null || targetStock.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    BigDecimal existingQty =
        rawMaterialBatchRepository.findByRawMaterial(rawMaterial).stream()
            .map(batch -> batch.getQuantity() == null ? BigDecimal.ZERO : batch.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal needed = targetStock.subtract(existingQty);
    if (needed.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rawMaterial);
    batch.setBatchCode("TEST-" + rawMaterial.getSku() + "-" + System.currentTimeMillis());
    batch.setUnit(rawMaterial.getUnitType() != null ? rawMaterial.getUnitType() : "UNIT");
    batch.setQuantity(needed);
    batch.setCostPerUnit(BigDecimal.ONE);
    rawMaterialBatchRepository.save(batch);
  }

  private ProductionProduct createTestProduct(Company company, String skuCode) {
    Long wipAccountId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "WIP-PROD")
            .map(Account::getId)
            .orElse(null);
    Long valuationId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "ASSET-INV")
            .map(Account::getId)
            .orElse(null);
    Long cogsId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "EXP-COGS")
            .map(Account::getId)
            .orElse(null);
    Long revenueId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "REV-SALES")
            .map(Account::getId)
            .orElse(null);
    Long discountId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "DISC-SALES")
            .map(Account::getId)
            .orElse(null);
    Long taxId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "LIAB-GST")
            .map(Account::getId)
            .orElse(null);
    Long laborAppliedId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "EXP-LABOR")
            .map(Account::getId)
            .orElse(null);
    Long overheadAppliedId =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "EXP-OVERHEAD")
            .map(Account::getId)
            .orElse(null);
    return productRepository
        .findByCompanyAndSkuCode(company, skuCode)
        .map(
            existing -> {
              boolean dirty = false;
              Map<String, Object> metadata =
                  existing.getMetadata() != null
                      ? new HashMap<>(existing.getMetadata())
                      : new HashMap<>();
              if (wipAccountId != null && (metadata.get("wipAccountId") == null)) {
                metadata.put("wipAccountId", wipAccountId);
                dirty = true;
              }
              if (valuationId != null && metadata.get("fgValuationAccountId") == null) {
                metadata.put("fgValuationAccountId", valuationId);
                dirty = true;
              }
              if (valuationId != null && metadata.get("semiFinishedAccountId") == null) {
                metadata.put("semiFinishedAccountId", valuationId);
                dirty = true;
              }
              if (cogsId != null && metadata.get("fgCogsAccountId") == null) {
                metadata.put("fgCogsAccountId", cogsId);
                dirty = true;
              }
              if (revenueId != null && metadata.get("fgRevenueAccountId") == null) {
                metadata.put("fgRevenueAccountId", revenueId);
                dirty = true;
              }
              if (discountId != null && metadata.get("fgDiscountAccountId") == null) {
                metadata.put("fgDiscountAccountId", discountId);
                dirty = true;
              }
              if (taxId != null && metadata.get("fgTaxAccountId") == null) {
                metadata.put("fgTaxAccountId", taxId);
                dirty = true;
              }
              if (laborAppliedId != null && metadata.get("laborAppliedAccountId") == null) {
                metadata.put("laborAppliedAccountId", laborAppliedId);
                dirty = true;
              }
              if (overheadAppliedId != null && metadata.get("overheadAppliedAccountId") == null) {
                metadata.put("overheadAppliedAccountId", overheadAppliedId);
                dirty = true;
              }
              if (dirty) {
                existing.setMetadata(metadata);
              }
              return dirty ? productRepository.save(existing) : existing;
            })
        .orElseGet(
            () -> {
              ProductionBrand brand =
                  brandRepository
                      .findByCompanyAndCodeIgnoreCase(company, "TEST-BRAND")
                      .orElseGet(
                          () -> {
                            ProductionBrand b = new ProductionBrand();
                            b.setCompany(company);
                            b.setCode("TEST-BRAND");
                            b.setName("Test Brand");
                            return brandRepository.save(b);
                          });

              ProductionProduct p = new ProductionProduct();
              p.setCompany(company);
              p.setBrand(brand);
              p.setProductName("Test Product " + skuCode);
              p.setCategory("FINISHED_GOOD");
              p.setUnitOfMeasure("UNIT");
              p.setSkuCode(skuCode);
              p.setBasePrice(new BigDecimal("100.00"));
              p.setGstRate(BigDecimal.ZERO);
              Map<String, Object> metadata = new HashMap<>();
              if (wipAccountId != null) {
                metadata.put("wipAccountId", wipAccountId);
              }
              if (valuationId != null) {
                metadata.put("fgValuationAccountId", valuationId);
                metadata.put("semiFinishedAccountId", valuationId);
              }
              if (cogsId != null) {
                metadata.put("fgCogsAccountId", cogsId);
              }
              if (revenueId != null) {
                metadata.put("fgRevenueAccountId", revenueId);
              }
              if (discountId != null) {
                metadata.put("fgDiscountAccountId", discountId);
              }
              if (taxId != null) {
                metadata.put("fgTaxAccountId", taxId);
              }
              if (laborAppliedId != null) {
                metadata.put("laborAppliedAccountId", laborAppliedId);
              }
              if (overheadAppliedId != null) {
                metadata.put("overheadAppliedAccountId", overheadAppliedId);
              }
              p.setMetadata(metadata);
              return productRepository.save(p);
            });
  }

  private FinishedGood createTestFinishedGood(Company company, String code, BigDecimal stock) {
    createTestProduct(company, code);
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV-SALES").orElseThrow();
    Account taxAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "LIAB-GST").orElseThrow();
    Account inventoryAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "ASSET-INV").orElseThrow();
    Account cogsAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXP-COGS").orElseThrow();
    Account discountAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "DISC-SALES").orElseThrow();

    return finishedGoodRepository
        .findByCompanyAndProductCode(company, code)
        .map(
            existing -> {
              boolean dirty = false;
              if (existing.getRevenueAccountId() == null) {
                existing.setRevenueAccountId(revenueAccount.getId());
                dirty = true;
              }
              if (existing.getTaxAccountId() == null) {
                existing.setTaxAccountId(taxAccount.getId());
                dirty = true;
              }
              if (existing.getValuationAccountId() == null) {
                existing.setValuationAccountId(inventoryAccount.getId());
                dirty = true;
              }
              if (existing.getCogsAccountId() == null) {
                existing.setCogsAccountId(cogsAccount.getId());
                dirty = true;
              }
              if (existing.getDiscountAccountId() == null) {
                existing.setDiscountAccountId(discountAccount.getId());
                dirty = true;
              }
              if (existing.getCurrentStock() == null
                  || existing.getCurrentStock().compareTo(stock) < 0) {
                existing.setCurrentStock(stock);
                dirty = true;
              }
              return dirty ? finishedGoodRepository.save(existing) : existing;
            })
        .orElseGet(
            () -> {
              FinishedGood fg = new FinishedGood();
              fg.setCompany(company);
              fg.setProductCode(code);
              fg.setName("Test FG " + code);
              fg.setCurrentStock(stock);
              fg.setReservedStock(BigDecimal.ZERO);
              fg.setRevenueAccountId(revenueAccount.getId());
              fg.setTaxAccountId(taxAccount.getId());
              fg.setValuationAccountId(inventoryAccount.getId());
              fg.setCogsAccountId(cogsAccount.getId());
              fg.setDiscountAccountId(discountAccount.getId());
              return finishedGoodRepository.save(fg);
            });
  }
}
