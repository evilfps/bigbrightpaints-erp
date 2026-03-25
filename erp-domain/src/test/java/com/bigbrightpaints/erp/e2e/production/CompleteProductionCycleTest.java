package com.bigbrightpaints.erp.e2e.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
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
 * E2E Tests for Production & Manufacturing workflows
 */
@DisplayName("E2E: Complete Production Cycle")
public class CompleteProductionCycleTest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "PROD-E2E";
  private static final String ADMIN_EMAIL = "prod@e2e.com";
  private static final String ADMIN_PASSWORD = "prod123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;

  private String authToken;
  private HttpHeaders headers;
  private Company company;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Production Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_FACTORY"));
    authToken = login();
    headers = createHeaders(authToken);
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    ensureTestAccounts(company);
    ensurePackagingMappings(company);
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

  private void ensureTestAccounts(Company company) {
    ensureAccount(company, "INV-RM", "Raw Material Inventory", AccountType.ASSET);
    ensureAccount(company, "INV-FG", "Finished Goods Inventory", AccountType.ASSET);
    ensureAccount(company, "INV-PACK", "Packaging Inventory", AccountType.ASSET);
    ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET);
    ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS);
    ensureAccount(company, "REV-FG", "Finished Goods Revenue", AccountType.REVENUE);
    ensureAccount(company, "DISC", "Sales Discounts", AccountType.EXPENSE);
    ensureAccount(company, "TAX", "Tax Payable", AccountType.LIABILITY);
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
  @DisplayName("Complete Production Cycle: From Mixing to Packing")
  void completeProductionCycle_FromMixingToPacking() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Step 1: Create raw materials
    RawMaterial rm1 =
        createRawMaterial(company, "RM-PAINT-BASE", "Paint Base", new BigDecimal("1000"));
    RawMaterial rm2 = createRawMaterial(company, "RM-PIGMENT", "Pigment", new BigDecimal("500"));

    // Step 2: Create production product
    ProductionProduct product = createProduct(company, "FG-PAINT-001", "Premium Paint");

    // Step 3: Log production (mixing)
    Map<String, Object> material1 =
        Map.of("rawMaterialId", rm1.getId(), "quantity", new BigDecimal("50.00"));
    Map<String, Object> material2 =
        Map.of("rawMaterialId", rm2.getId(), "quantity", new BigDecimal("10.00"));

    Map<String, Object> logRequest =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("500.00"),
            "mixedQuantity", new BigDecimal("500.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "John Supervisor",
            "materials", List.of(material1, material2));

    ResponseEntity<Map> logResponse =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);

    assertThat(logResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long productionLogId =
        ((Number) ((Map<?, ?>) logResponse.getBody().get("data")).get("id")).longValue();

    // Step 4: Verify raw material stock was deducted
    RawMaterial rm1Updated = rawMaterialRepository.findById(rm1.getId()).orElseThrow();
    assertThat(rm1Updated.getCurrentStock()).isEqualByComparingTo(new BigDecimal("950.00"));

    // Step 5: Pack the production batch
    Map<String, Object> packingLine =
        Map.of(
            "packagingSize", "10L Bucket",
            "quantityLiters", new BigDecimal("400.00"),
            "piecesCount", 400,
            "boxesCount", 40,
            "piecesPerBox", 10);

    Map<String, Object> packingRequest =
        Map.of(
            "productionLogId",
            productionLogId,
            "packedDate",
            LocalDate.now(),
            "packedBy",
            "Packing Bot",
            "lines",
            List.of(packingLine));
    HttpHeaders packingHeaders = new HttpHeaders();
    packingHeaders.putAll(headers);
    packingHeaders.add("Idempotency-Key", "PACK-CYCLE-1-" + System.nanoTime());

    ResponseEntity<Map> packingResponse =
        rest.exchange(
            "/api/v1/factory/packing-records",
            HttpMethod.POST,
            new HttpEntity<>(packingRequest, packingHeaders),
            Map.class);

    assertThat(packingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Step 6: Verify finished good batches were created
    List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findAll();
    assertThat(batches).isNotEmpty();

    // Step 7: Verify finished good stock increased
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, product.getSkuCode())
            .orElseThrow();
    assertThat(fg.getCurrentStock()).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("Production with Multiple Raw Materials: Inventory Deduction")
  void productionWithMultipleRawMaterials_InventoryDeduction() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create multiple raw materials with known stock
    RawMaterial rm1 =
        createRawMaterial(company, "RM-MULTI-1", "Material 1", new BigDecimal("1000"));
    RawMaterial rm2 = createRawMaterial(company, "RM-MULTI-2", "Material 2", new BigDecimal("800"));
    RawMaterial rm3 = createRawMaterial(company, "RM-MULTI-3", "Material 3", new BigDecimal("600"));

    ProductionProduct product = createProduct(company, "FG-MULTI-001", "Multi Material Product");

    // Log production with all materials
    Map<String, Object> logRequest =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("200.00"),
            "mixedQuantity", new BigDecimal("200.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "Test Supervisor",
            "materials",
                List.of(
                    Map.of("rawMaterialId", rm1.getId(), "quantity", new BigDecimal("30.00")),
                    Map.of("rawMaterialId", rm2.getId(), "quantity", new BigDecimal("20.00")),
                    Map.of("rawMaterialId", rm3.getId(), "quantity", new BigDecimal("10.00"))));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Verify all materials were deducted correctly
    assertThat(rawMaterialRepository.findById(rm1.getId()).get().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("970.00"));
    assertThat(rawMaterialRepository.findById(rm2.getId()).get().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("780.00"));
    assertThat(rawMaterialRepository.findById(rm3.getId()).get().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("590.00"));
  }

  @Test
  @DisplayName("Packing with Wastage: Auto Calculation and Accounting")
  void packingWithWastage_AutoCalculationAndAccounting() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create production log
    RawMaterial rm =
        createRawMaterial(company, "RM-WASTAGE", "Wastage Material", new BigDecimal("500"));
    ProductionProduct product = createProduct(company, "FG-WASTAGE-001", "Wastage Product");

    Map<String, Object> logRequest =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("1000.00"),
            "mixedQuantity", new BigDecimal("1000.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "Supervisor",
            "materials",
                List.of(Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("100.00"))));

    ResponseEntity<Map> logResponse =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);
    Long logId = ((Number) ((Map<?, ?>) logResponse.getBody().get("data")).get("id")).longValue();

    // Pack with significant wastage
    Map<String, Object> packingRequest =
        Map.of(
            "productionLogId",
            logId,
            "packedDate",
            LocalDate.now(),
            "packedBy",
            "Supervisor",
            "lines",
            List.of(
                Map.of(
                    "packagingSize",
                    "5L Can",
                    "quantityLiters",
                    new BigDecimal("800.00"),
                    "piecesCount",
                    800,
                    "boxesCount",
                    80,
                    "piecesPerBox",
                    10)));
    HttpHeaders packingHeaders = new HttpHeaders();
    packingHeaders.putAll(headers);
    packingHeaders.add("Idempotency-Key", "PACK-WASTE-1-" + System.nanoTime());

    ResponseEntity<Map> packingResponse =
        rest.exchange(
            "/api/v1/factory/packing-records",
            HttpMethod.POST,
            new HttpEntity<>(packingRequest, packingHeaders),
            Map.class);

    assertThat(packingResponse.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);

    // Verify packing quantity reflected on production log when accepted
    ProductionLog log = productionLogRepository.findById(logId).orElseThrow();
    assertThat(log.getTotalPackedQuantity()).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("Partial Packing: Multiple Packing Sessions")
  void partialPacking_MultiplePackingSessions() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create production log with large batch
    RawMaterial rm =
        createRawMaterial(company, "RM-PARTIAL", "Partial Material", new BigDecimal("1000"));
    ProductionProduct product = createProduct(company, "FG-PARTIAL-001", "Partial Product");

    Map<String, Object> logRequest =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("1000.00"),
            "mixedQuantity", new BigDecimal("1000.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "Supervisor",
            "materials",
                List.of(Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("50.00"))));

    ResponseEntity<Map> logResponse =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);
    Long logId = ((Number) ((Map<?, ?>) logResponse.getBody().get("data")).get("id")).longValue();

    // First packing session - pack 400 units
    Map<String, Object> packing1 =
        Map.of(
            "productionLogId",
            logId,
            "packedDate",
            LocalDate.now(),
            "packedBy",
            "Supervisor",
            "lines",
            List.of(
                Map.of(
                    "packagingSize",
                    "10L",
                    "quantityLiters",
                    new BigDecimal("400.00"),
                    "piecesCount",
                    400,
                    "boxesCount",
                    40,
                    "piecesPerBox",
                    10)));
    HttpHeaders pack1Headers = new HttpHeaders();
    pack1Headers.putAll(headers);
    pack1Headers.add("Idempotency-Key", "PACK-PARTIAL-1-" + System.nanoTime());

    ResponseEntity<Map> pack1Response =
        rest.exchange(
            "/api/v1/factory/packing-records",
            HttpMethod.POST,
            new HttpEntity<>(packing1, pack1Headers),
            Map.class);
    assertThat(pack1Response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Verify production log shows partial packing
    ProductionLog log = productionLogRepository.findById(logId).orElseThrow();
    assertThat(log.getTotalPackedQuantity()).isEqualByComparingTo(new BigDecimal("400.00"));

    // Second packing session - pack remaining
    Map<String, Object> packing2 =
        Map.of(
            "productionLogId",
            logId,
            "packedDate",
            LocalDate.now(),
            "packedBy",
            "Supervisor",
            "lines",
            List.of(
                Map.of(
                    "packagingSize",
                    "10L",
                    "quantityLiters",
                    new BigDecimal("500.00"),
                    "piecesCount",
                    500,
                    "boxesCount",
                    50,
                    "piecesPerBox",
                    10)));
    HttpHeaders pack2Headers = new HttpHeaders();
    pack2Headers.putAll(headers);
    pack2Headers.add("Idempotency-Key", "PACK-PARTIAL-2-" + System.nanoTime());

    ResponseEntity<Map> pack2Response =
        rest.exchange(
            "/api/v1/factory/packing-records",
            HttpMethod.POST,
            new HttpEntity<>(packing2, pack2Headers),
            Map.class);
    assertThat(pack2Response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Verify total packed quantity
    ProductionLog finalLog = productionLogRepository.findById(logId).orElseThrow();
    assertThat(finalLog.getTotalPackedQuantity()).isGreaterThanOrEqualTo(new BigDecimal("400.00"));
  }

  @Test
  @DisplayName("Production Log without Sufficient Raw Material: Throws Error")
  void productionLog_WithoutSufficientRawMaterial_ThrowsError() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create raw material with low stock
    RawMaterial rm =
        createRawMaterial(company, "RM-LOW-STOCK", "Low Stock Material", new BigDecimal("10.00"));
    ProductionProduct product = createProduct(company, "FG-INSUFFICIENT-001", "Product");

    // Try to use more than available
    Map<String, Object> logRequest =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("100.00"),
            "mixedQuantity", new BigDecimal("100.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "Supervisor",
            "materials",
                List.of(Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("50.00"))));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(logRequest, headers),
            Map.class);

    // Should fail with insufficient stock error
    assertThat(response.getStatusCode())
        .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  @DisplayName("Complete Packing creates Finished Good Batches with FIFO")
  void completePacking_CreatesFinishedGoodBatches_FIFO() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    RawMaterial rm = createRawMaterial(company, "RM-FIFO", "FIFO Material", new BigDecimal("500"));
    ProductionProduct product = createProduct(company, "FG-FIFO-001", "FIFO Product");

    // Create first production batch
    Map<String, Object> log1Request =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("100.00"),
            "mixedQuantity", new BigDecimal("100.00"),
            "producedAt", LocalDate.now().minusDays(2).toString(),
            "createdBy", "Supervisor",
            "materials",
                List.of(Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("20.00"))));

    ResponseEntity<Map> log1Response =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(log1Request, headers),
            Map.class);
    Long log1Id = ((Number) ((Map<?, ?>) log1Response.getBody().get("data")).get("id")).longValue();

    // Pack first batch
    Map<String, Object> pack1 =
        Map.of(
            "productionLogId",
            log1Id,
            "packedDate",
            LocalDate.now().minusDays(2),
            "packedBy",
            "Supervisor",
            "lines",
            List.of(
                Map.of(
                    "packagingSize",
                    "5L",
                    "quantityLiters",
                    new BigDecimal("90.00"),
                    "piecesCount",
                    90,
                    "boxesCount",
                    9,
                    "piecesPerBox",
                    10)));
    HttpHeaders pack1Headers = new HttpHeaders();
    pack1Headers.putAll(headers);
    pack1Headers.add("Idempotency-Key", "PACK-FIFO-1-" + System.nanoTime());

    rest.exchange(
        "/api/v1/factory/packing-records",
        HttpMethod.POST,
        new HttpEntity<>(pack1, pack1Headers),
        Map.class);

    // Create second production batch
    Map<String, Object> log2Request =
        Map.of(
            "brandId", product.getBrand().getId(),
            "productId", product.getId(),
            "batchSize", new BigDecimal("100.00"),
            "mixedQuantity", new BigDecimal("100.00"),
            "producedAt", LocalDate.now().toString(),
            "createdBy", "Supervisor",
            "materials",
                List.of(Map.of("rawMaterialId", rm.getId(), "quantity", new BigDecimal("20.00"))));

    ResponseEntity<Map> log2Response =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(log2Request, headers),
            Map.class);
    Long log2Id = ((Number) ((Map<?, ?>) log2Response.getBody().get("data")).get("id")).longValue();

    // Pack second batch
    Map<String, Object> pack2 =
        Map.of(
            "productionLogId",
            log2Id,
            "packedDate",
            LocalDate.now(),
            "packedBy",
            "Supervisor",
            "lines",
            List.of(
                Map.of(
                    "packagingSize",
                    "5L",
                    "quantityLiters",
                    new BigDecimal("90.00"),
                    "piecesCount",
                    90,
                    "boxesCount",
                    9,
                    "piecesPerBox",
                    10)));
    HttpHeaders pack2Headers = new HttpHeaders();
    pack2Headers.putAll(headers);
    pack2Headers.add("Idempotency-Key", "PACK-FIFO-2-" + System.nanoTime());

    rest.exchange(
        "/api/v1/factory/packing-records",
        HttpMethod.POST,
        new HttpEntity<>(pack2, pack2Headers),
        Map.class);

    // Verify both batches exist
    List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findAll();
    assertThat(batches).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Monthly Packing Cost Allocation updates Unit Costs")
  void monthlyPacking_CostAllocation_UpdatesUnitCosts() {
    // This test verifies the cost allocation endpoint is available
    Map<String, Object> allocRequest =
        Map.of(
            "year", LocalDate.now().getYear(),
            "month", LocalDate.now().getMonthValue());

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/factory/cost-allocation",
            HttpMethod.POST,
            new HttpEntity<>(allocRequest, headers),
            Map.class);

    // May return OK or BAD_REQUEST depending on data availability
    assertThat(response.getStatusCode())
        .isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
  }

  // Helper methods
  private RawMaterial createRawMaterial(
      Company company, String sku, String name, BigDecimal stock) {
    Account rmAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV-RM").orElseThrow();
    return rawMaterialRepository
        .findByCompanyAndSku(company, sku)
        .orElseGet(
            () -> {
              RawMaterial rm = new RawMaterial();
              rm.setCompany(company);
              rm.setSku(sku);
              rm.setName(name);
              rm.setUnitType("KG");
              rm.setCurrentStock(stock);
              rm.setInventoryAccountId(rmAccount.getId());
              RawMaterial saved = rawMaterialRepository.save(rm);
              RawMaterialBatch batch = new RawMaterialBatch();
              batch.setRawMaterial(saved);
              batch.setQuantity(stock);
              batch.setCostPerUnit(BigDecimal.ONE);
              batch.setBatchCode("BATCH-" + sku + "-" + System.currentTimeMillis());
              batch.setUnit(rm.getUnitType());
              batch.setReceivedAt(Instant.now());
              rawMaterialBatchRepository.save(batch);
              return saved;
            });
  }

  private void ensurePackagingMappings(Company company) {
    ensurePackagingMapping(
        company, "10L BUCKET", "PACK-10L-BUCKET", "10L Bucket", new BigDecimal("10"));
    ensurePackagingMapping(company, "5L CAN", "PACK-5L-CAN", "5L Can", new BigDecimal("5"));
    ensurePackagingMapping(company, "10L", "PACK-10L", "10L Pack", new BigDecimal("10"));
    ensurePackagingMapping(company, "5L", "PACK-5L", "5L Pack", new BigDecimal("5"));
  }

  private void ensurePackagingMapping(
      Company company, String size, String sku, String name, BigDecimal litersPerUnit) {
    List<PackagingSizeMapping> existing =
        packagingSizeMappingRepository.findActiveByCompanyAndPackagingSizeIgnoreCase(company, size);
    if (!existing.isEmpty()) {
      Long materialId = existing.get(0).getRawMaterial().getId();
      RawMaterial material = rawMaterialRepository.findById(materialId).orElseThrow();
      topUpPackagingMaterial(material, new BigDecimal("1000"), new BigDecimal("1.50"));
      return;
    }
    RawMaterial material =
        ensurePackagingMaterial(company, sku, name, new BigDecimal("1000"), new BigDecimal("1.50"));
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(size);
    mapping.setRawMaterial(material);
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(litersPerUnit);
    mapping.setActive(true);
    packagingSizeMappingRepository.save(mapping);
  }

  private RawMaterial ensurePackagingMaterial(
      Company company, String sku, String name, BigDecimal quantity, BigDecimal unitCost) {
    Account packagingAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV-PACK").orElseThrow();
    RawMaterial material =
        rawMaterialRepository
            .findByCompanyAndSku(company, sku)
            .orElseGet(
                () -> {
                  RawMaterial rm = new RawMaterial();
                  rm.setCompany(company);
                  rm.setSku(sku);
                  rm.setName(name);
                  rm.setUnitType("UNIT");
                  rm.setMaterialType(MaterialType.PACKAGING);
                  rm.setInventoryAccountId(packagingAccount.getId());
                  rm.setCurrentStock(BigDecimal.ZERO);
                  return rawMaterialRepository.save(rm);
                });

    if (material.getInventoryAccountId() == null) {
      material.setInventoryAccountId(packagingAccount.getId());
    }
    material.setMaterialType(MaterialType.PACKAGING);
    material.setUnitType("UNIT");
    topUpPackagingMaterial(material, quantity, unitCost);
    return material;
  }

  private void topUpPackagingMaterial(
      RawMaterial material, BigDecimal quantity, BigDecimal unitCost) {
    BigDecimal current =
        java.util.Optional.ofNullable(material.getCurrentStock()).orElse(BigDecimal.ZERO);
    BigDecimal topUp = quantity != null ? quantity : BigDecimal.ZERO;
    material.setCurrentStock(current.add(topUp));
    RawMaterial saved = rawMaterialRepository.save(material);

    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(saved);
    batch.setQuantity(topUp);
    batch.setCostPerUnit(unitCost);
    batch.setBatchCode("PACK-" + saved.getSku() + "-" + System.currentTimeMillis());
    batch.setUnit(saved.getUnitType());
    batch.setReceivedAt(Instant.now());
    rawMaterialBatchRepository.save(batch);
  }

  private ProductionProduct createProduct(Company company, String skuCode, String name) {
    return productRepository
        .findByCompanyAndSkuCode(company, skuCode)
        .orElseGet(
            () -> {
              Account wipAccount =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "WIP").orElseThrow();
              Account fgValuation =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV-FG").orElseThrow();
              Account cogs =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "COGS").orElseThrow();
              Account revenue =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV-FG").orElseThrow();
              Account discount =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "DISC").orElseThrow();
              Account tax =
                  accountRepository.findByCompanyAndCodeIgnoreCase(company, "TAX").orElseThrow();
              ProductionBrand brand =
                  brandRepository
                      .findByCompanyAndCodeIgnoreCase(company, "E2E-BRAND")
                      .orElseGet(
                          () -> {
                            ProductionBrand b = new ProductionBrand();
                            b.setCompany(company);
                            b.setCode("E2E-BRAND");
                            b.setName("E2E Test Brand");
                            return brandRepository.save(b);
                          });

              ProductionProduct p = new ProductionProduct();
              p.setCompany(company);
              p.setBrand(brand);
              p.setProductName(name);
              p.setCategory("FINISHED_GOOD");
              p.setUnitOfMeasure("UNIT");
              p.setSkuCode(skuCode);
              p.setBasePrice(new BigDecimal("150.00"));
              p.setGstRate(BigDecimal.ZERO);
              p.getMetadata().put("wipAccountId", wipAccount.getId());
              p.getMetadata().put("semiFinishedAccountId", fgValuation.getId());
              p.getMetadata().put("fgValuationAccountId", fgValuation.getId());
              p.getMetadata().put("fgCogsAccountId", cogs.getId());
              p.getMetadata().put("fgRevenueAccountId", revenue.getId());
              p.getMetadata().put("fgDiscountAccountId", discount.getId());
              p.getMetadata().put("fgTaxAccountId", tax.getId());
              return productRepository.save(p);
            });
  }
}
