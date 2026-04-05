package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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

@Tag("critical")
class AccountingCatalogControllerSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CAT-SURFACE-RETIRE";
  private static final String PASSWORD = "changeme";
  private static final String ADMIN_EMAIL = "catalog-surface-retire@bbp.com";
  private static final String ACCOUNTING_EMAIL = "catalog-surface-accounting@bbp.com";
  private static final String SALES_EMAIL = "catalog-surface-sales@bbp.com";
  private static final String FACTORY_EMAIL = "catalog-surface-factory@bbp.com";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

  private Company company;
  private Account inventoryAccount;
  private Account cogsAccount;
  private Account revenueAccount;
  private Account discountAccount;
  private Account taxAccount;
  private Account wipAccount;
  private Account laborAppliedAccount;
  private Account overheadAppliedAccount;
  private HttpHeaders headers;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Surface Retirement Co");
    inventoryAccount = ensureAccount("INV", "Inventory", AccountType.ASSET);
    cogsAccount = ensureAccount("COGS", "COGS", AccountType.COGS);
    revenueAccount = ensureAccount("REV", "Revenue", AccountType.REVENUE);
    discountAccount = ensureAccount("DISC", "Discount", AccountType.EXPENSE);
    taxAccount = ensureAccount("GST-OUT", "GST Output", AccountType.LIABILITY);
    wipAccount = ensureAccount("WIP", "Work In Progress", AccountType.ASSET);
    laborAppliedAccount = ensureAccount("LAB", "Labor Applied", AccountType.ASSET);
    overheadAppliedAccount = ensureAccount("OVH", "Overhead Applied", AccountType.ASSET);

    company.setDefaultInventoryAccountId(inventoryAccount.getId());
    company.setDefaultCogsAccountId(cogsAccount.getId());
    company.setDefaultRevenueAccountId(revenueAccount.getId());
    company.setDefaultDiscountAccountId(discountAccount.getId());
    company.setDefaultTaxAccountId(taxAccount.getId());
    company.setGstOutputTaxAccountId(taxAccount.getId());
    companyRepository.save(company);

    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Catalog Surface Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Catalog Surface Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SALES_EMAIL, PASSWORD, "Catalog Surface Sales", COMPANY_CODE, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        FACTORY_EMAIL, PASSWORD, "Catalog Surface Factory", COMPANY_CODE, List.of("ROLE_FACTORY"));
    headers = authHeaders();
  }

  @Test
  void existingBrandFlow_usesCanonicalBrandList_andRemainsDownstreamReady() {
    ProductionBrand activeBrand = saveBrand("Existing Flow Active " + shortId(), true);
    ProductionBrand inactiveBrand = saveBrand("Existing Flow Inactive " + shortId(), false);

    ResponseEntity<Map> brandListResponse =
        rest.exchange(
            "/api/v1/catalog/brands?active=true",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(brandListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> brandList = dataList(brandListResponse);
    assertThat(brandList)
        .extracting(brand -> ((Number) brand.get("id")).longValue())
        .contains(activeBrand.getId())
        .doesNotContain(inactiveBrand.getId());
    assertThat(brandList).allMatch(brand -> Boolean.TRUE.equals(brand.get("active")));

    DownstreamFlowResult flow =
        runDownstreamReadyFlow(activeBrand.getId(), "Existing Flow Primer " + shortId());
    assertThat(flow.brandId()).isEqualTo(activeBrand.getId());
    assertThat(flow.sku()).startsWith("FG-");
  }

  @Test
  void newBrandFlow_usesCanonicalBrandCreate_andRemainsDownstreamReady() {
    ResponseEntity<Map> createBrandResponse =
        rest.exchange(
            "/api/v1/catalog/brands",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "name",
                    "New Flow Brand " + shortId(),
                    "description",
                    "Created on canonical host",
                    "active",
                    true),
                headers),
            Map.class);

    assertThat(createBrandResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> createdBrand = data(createBrandResponse);
    Long brandId = ((Number) createdBrand.get("id")).longValue();
    assertThat(createdBrand.get("code")).isNotNull();

    ResponseEntity<Map> brandListResponse =
        rest.exchange(
            "/api/v1/catalog/brands?active=true",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(brandListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dataList(brandListResponse))
        .extracting(brand -> ((Number) brand.get("id")).longValue())
        .contains(brandId);

    DownstreamFlowResult flow = runDownstreamReadyFlow(brandId, "New Flow Primer " + shortId());
    assertThat(flow.brandId()).isEqualTo(brandId);
  }

  @Test
  void retiredAccountingAndProductionRoutes_areUnmappedForAuthenticatedCallers() {
    assertRetiredRouteNotFound(
        HttpMethod.POST, "/api/v1/accounting/catalog/import", Map.of("ignored", true));
    assertRetiredRouteNotFound(HttpMethod.GET, "/api/v1/accounting/catalog/products", null);
    assertRetiredRouteNotFound(
        HttpMethod.POST, "/api/v1/accounting/catalog/products", Map.of("ignored", true));
    assertRetiredRouteNotFound(
        HttpMethod.PUT, "/api/v1/accounting/catalog/products/999", Map.of("ignored", true));
    assertRetiredRouteNotFound(
        HttpMethod.POST,
        "/api/v1/accounting/catalog/products/bulk-variants",
        Map.of("ignored", true));
    assertRetiredRouteNotFound(
        HttpMethod.POST,
        "/api/v1/accounting/catalog/products/bulk-variants?dryRun=true",
        Map.of("ignored", true));
    assertRetiredRouteNotFound(HttpMethod.GET, "/api/v1/production/brands", null);
    assertRetiredRouteNotFound(HttpMethod.GET, "/api/v1/production/brands/999/products", null);
  }

  @Test
  void canonicalImportRoute_allowsAdminAndAccounting_only() {
    String csvRows =
        "brand,product_name,sku,category,unit_of_measure,hsn_code,gst_rate,base_price,color,size\n"
            + "Surface Brand,Surface"
            + " Primer,SURFACE-PRIMER-001,FINISHED_GOOD,LITER,320910,18,1200,WHITE,1L\n";

    ResponseEntity<Map> adminResponse =
        importCatalog(
            authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE), csvRows, "catalog-import-admin.csv");
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> accountingResponse =
        importCatalog(
            authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE),
            csvRows,
            "catalog-import-accounting.csv");
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> salesResponse =
        importCatalog(
            authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE), csvRows, "catalog-import-sales.csv");
    assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> factoryResponse =
        importCatalog(
            authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE),
            csvRows,
            "catalog-import-factory.csv");
    assertThat(factoryResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void canonicalItemEntryRoute_allowsAdminAndAccounting_only() {
    ProductionBrand activeBrand = saveBrand("Single Route Brand " + shortId(), true);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(activeBrand.getId(), "Canonical Entry " + shortId()),
                authHeaders()),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(adminResponse)).containsKeys("id", "code");

    ResponseEntity<Map> accountingResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(activeBrand.getId(), "Canonical Preview " + shortId()),
                authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(accountingResponse)).containsKeys("id", "code");

    ResponseEntity<Map> salesResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(
                    activeBrand.getId(), "Canonical Sales Block " + shortId()),
                authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> factoryResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(
                    activeBrand.getId(), "Canonical Factory Block " + shortId()),
                authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    assertThat(factoryResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void retiredSingleAndBulkVariantRoutes_areUnavailableForAuthenticatedCallers() {
    ProductionBrand activeBrand = saveBrand("Retired Route Brand " + shortId(), true);
    assertRetiredWriteRouteUnavailable(
        "/api/v1/catalog/products/single",
        singleProductPayload(activeBrand.getId(), "CAT-SINGLE-" + shortId()));
    assertRetiredWriteRouteUnavailable(
        "/api/v1/catalog/products/bulk-variants?dryRun=true",
        bulkVariantPayload("Dry Run Brand " + shortId(), "N" + shortId().substring(0, 5)));
  }

  @Test
  void catalogReadiness_masksAccountingSpecificBlockersForNonAccountingReaders() {
    ProductionBrand brand = saveBrand("Readiness Mask " + shortId(), true);

    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(brand.getId(), "Readiness Mask Product " + shortId()),
                headers),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> createdItem = data(createResponse);
    String sku = String.valueOf(createdItem.get("code"));
    Long productId = ((Number) createdItem.get("id")).longValue();

    Map<String, Object> browsedProduct = browseProduct(authHeaders(), brand.getId(), sku);

    ProductionProduct product =
        productionProductRepository.findByCompanyAndSkuCode(company, sku).orElseThrow();
    Map<String, Object> degradedMetadata = new LinkedHashMap<>(product.getMetadata());
    degradedMetadata.remove("wipAccountId");
    product.setMetadata(degradedMetadata);
    productionProductRepository.save(product);

    finishedGoodRepository
        .findByCompanyAndProductCode(company, sku)
        .ifPresentOrElse(
            finishedGood -> {
              finishedGood.setValuationAccountId(null);
              finishedGood.setCogsAccountId(null);
              finishedGood.setRevenueAccountId(null);
              finishedGood.setTaxAccountId(null);
              finishedGoodRepository.save(finishedGood);
            },
            () -> {
              throw new AssertionError("expected finished-good mirror for sku " + sku);
            });

    ResponseEntity<Map> salesBrowseResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=" + sku + "&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    ResponseEntity<Map> accountingBrowseResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=" + sku + "&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    ResponseEntity<Map> salesReadResponse =
        rest.exchange(
            "/api/v1/catalog/items/" + productId,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);
    ResponseEntity<Map> accountingReadResponse =
        rest.exchange(
            "/api/v1/catalog/items/" + productId,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE)),
            Map.class);

    Map<String, Object> salesBrowseProduct = browseProduct(salesBrowseResponse, sku);
    Map<String, Object> accountingBrowseProduct = browseProduct(accountingBrowseResponse, sku);
    Map<String, Object> salesReadProduct = data(salesReadResponse);
    Map<String, Object> accountingReadProduct = data(accountingReadResponse);

    assertThat(readinessBlockers(salesBrowseProduct, "inventoryReady"))
        .containsExactly("ACCOUNTING_CONFIGURATION_REQUIRED");
    assertThat(readinessBlockers(salesBrowseProduct, "productionReady"))
        .containsExactly("ACCOUNTING_CONFIGURATION_REQUIRED");
    assertThat(readinessBlockers(salesBrowseProduct, "salesReady"))
        .containsExactly("NO_FINISHED_GOOD_BATCH_STOCK", "ACCOUNTING_CONFIGURATION_REQUIRED");
    assertThat(readinessBlockers(salesReadProduct, "inventoryReady"))
        .containsExactly("ACCOUNTING_CONFIGURATION_REQUIRED");
    assertThat(readinessBlockers(salesReadProduct, "productionReady"))
        .containsExactly("ACCOUNTING_CONFIGURATION_REQUIRED");
    assertThat(readinessBlockers(salesReadProduct, "salesReady"))
        .containsExactly("NO_FINISHED_GOOD_BATCH_STOCK", "ACCOUNTING_CONFIGURATION_REQUIRED");

    assertThat(readinessBlockers(accountingBrowseProduct, "inventoryReady"))
        .containsExactlyInAnyOrder(
            "FINISHED_GOOD_VALUATION_ACCOUNT_MISSING",
            "FINISHED_GOOD_COGS_ACCOUNT_MISSING",
            "FINISHED_GOOD_REVENUE_ACCOUNT_MISSING",
            "FINISHED_GOOD_TAX_ACCOUNT_MISSING");
    assertThat(readinessBlockers(accountingBrowseProduct, "productionReady"))
        .containsExactlyInAnyOrder(
            "FINISHED_GOOD_VALUATION_ACCOUNT_MISSING",
            "FINISHED_GOOD_COGS_ACCOUNT_MISSING",
            "FINISHED_GOOD_REVENUE_ACCOUNT_MISSING",
            "FINISHED_GOOD_TAX_ACCOUNT_MISSING",
            "WIP_ACCOUNT_MISSING");
    assertThat(readinessBlockers(accountingReadProduct, "inventoryReady"))
        .containsExactlyInAnyOrder(
            "FINISHED_GOOD_VALUATION_ACCOUNT_MISSING",
            "FINISHED_GOOD_COGS_ACCOUNT_MISSING",
            "FINISHED_GOOD_REVENUE_ACCOUNT_MISSING",
            "FINISHED_GOOD_TAX_ACCOUNT_MISSING");
    assertThat(readinessBlockers(accountingReadProduct, "productionReady"))
        .containsExactlyInAnyOrder(
            "FINISHED_GOOD_VALUATION_ACCOUNT_MISSING",
            "FINISHED_GOOD_COGS_ACCOUNT_MISSING",
            "FINISHED_GOOD_REVENUE_ACCOUNT_MISSING",
            "FINISHED_GOOD_TAX_ACCOUNT_MISSING",
            "WIP_ACCOUNT_MISSING");
  }

  @Test
  void catalogStock_isHiddenFromSalesButVisibleToAdminAccountingAndFactory() {
    ProductionBrand brand = saveBrand("Stock Visibility " + shortId(), true);

    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                canonicalFinishedGoodPayload(
                    brand.getId(), "Stock Visibility Product " + shortId()),
                headers),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> createdItem = data(createResponse);
    String sku = String.valueOf(createdItem.get("code"));
    Long productId = ((Number) createdItem.get("id")).longValue();

    Map<String, Object> adminBrowse = browseProduct(searchItemsWithStock(authHeaders(), sku), sku);
    Map<String, Object> accountingBrowse =
        browseProduct(
            searchItemsWithStock(authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE), sku), sku);
    Map<String, Object> factoryBrowse =
        browseProduct(
            searchItemsWithStock(authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE), sku), sku);
    Map<String, Object> salesBrowse =
        browseProduct(
            searchItemsWithStock(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE), sku), sku);

    ResponseEntity<Map> adminReadResponse = getItemWithStock(authHeaders(), productId);
    ResponseEntity<Map> accountingReadResponse =
        getItemWithStock(authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE), productId);
    ResponseEntity<Map> factoryReadResponse =
        getItemWithStock(authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE), productId);
    ResponseEntity<Map> salesReadResponse =
        getItemWithStock(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE), productId);

    assertThat(adminBrowse.get("stock")).isInstanceOf(Map.class);
    assertThat(accountingBrowse.get("stock")).isInstanceOf(Map.class);
    assertThat(factoryBrowse.get("stock")).isInstanceOf(Map.class);
    assertThat(salesBrowse.get("stock")).isNull();

    assertThat(data(adminReadResponse).get("stock")).isInstanceOf(Map.class);
    assertThat(data(accountingReadResponse).get("stock")).isInstanceOf(Map.class);
    assertThat(data(factoryReadResponse).get("stock")).isInstanceOf(Map.class);
    assertThat(data(salesReadResponse).get("stock")).isNull();
  }

  private DownstreamFlowResult runDownstreamReadyFlow(Long brandId, String baseProductName) {
    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(canonicalFinishedGoodPayload(brandId, baseProductName), headers),
            Map.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> createData = data(createResponse);
    String sku = String.valueOf(createData.get("code"));
    assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isPresent();

    ResponseEntity<Map> browseResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=" + sku + "&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(browseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> browseContent =
        (List<Map<String, Object>>) data(browseResponse).get("content");
    Map<String, Object> browsedProduct =
        browseContent.stream()
            .filter(candidate -> sku.equals(String.valueOf(candidate.get("code"))))
            .findFirst()
            .orElseThrow();

    Long productId = ((Number) browsedProduct.get("id")).longValue();
    Long browsedBrandId = ((Number) browsedProduct.get("brandId")).longValue();

    ResponseEntity<Map> salesOrderResponse =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(salesOrderPayload(sku), headers),
            Map.class);
    assertThat(salesOrderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(salesOrderResponse)).containsKeys("id", "orderNumber", "status");

    RawMaterial rawMaterial =
        createRawMaterialWithBatch(
            "RM-SURFACE-" + shortId(), "Surface Binder " + shortId(), new BigDecimal("50.00"));

    ResponseEntity<Map> productionLogResponse =
        rest.exchange(
            "/api/v1/factory/production/logs",
            HttpMethod.POST,
            new HttpEntity<>(
                productionLogPayload(browsedBrandId, productId, rawMaterial.getId()), headers),
            Map.class);
    assertThat(productionLogResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> productionData = data(productionLogResponse);
    assertThat(productionData).containsKeys("id", "productionCode");
    String productionCode = String.valueOf(productionData.get("productionCode"));

    RawMaterial semiFinished =
        rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, sku + "-BULK").orElseThrow();
    assertThat(
            rawMaterialBatchRepository.findByRawMaterialAndBatchCode(semiFinished, productionCode))
        .isPresent();

    return new DownstreamFlowResult(sku, productId, browsedBrandId);
  }

  private void assertRetiredRouteNotFound(HttpMethod method, String path, Object body) {
    HttpEntity<?> entity =
        body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    ResponseEntity<Map> response = rest.exchange(path, method, entity, Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private void assertRetiredWriteRouteUnavailable(String path, Object body) {
    ResponseEntity<Map> response =
        rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
  }

  private Account ensureAccount(String code, String name, AccountType type) {
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

  private ProductionBrand saveBrand(String name, boolean active) {
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setName(name);
    brand.setCode(("CSR" + shortId()).substring(0, 10));
    brand.setActive(active);
    return productionBrandRepository.save(brand);
  }

  private HttpHeaders authHeaders() {
    return authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE);
  }

  private HttpHeaders authHeaders(String email, String password, String companyCode) {
    Map<String, Object> loginPayload =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", companyCode);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    authHeaders.set("X-Company-Code", companyCode);
    authHeaders.setContentType(MediaType.APPLICATION_JSON);
    return authHeaders;
  }

  private ResponseEntity<Map> importCatalog(
      HttpHeaders requestHeaders, String csvPayload, String fileName) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    body.add("file", new HttpEntity<>(csvResource(fileName, csvPayload), fileHeaders));

    HttpHeaders multipartHeaders = new HttpHeaders();
    multipartHeaders.putAll(requestHeaders);
    multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    return rest.exchange(
        "/api/v1/catalog/import",
        HttpMethod.POST,
        new HttpEntity<>(body, multipartHeaders),
        Map.class);
  }

  private ByteArrayResource csvResource(String fileName, String csvPayload) {
    return new ByteArrayResource(csvPayload.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return fileName;
      }
    };
  }

  private Map<String, Object> canonicalFinishedGoodPayload(Long brandId, String baseProductName) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("wipAccountId", wipAccount.getId());
    metadata.put("laborAppliedAccountId", laborAppliedAccount.getId());
    metadata.put("overheadAppliedAccountId", overheadAppliedAccount.getId());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("name", baseProductName);
    payload.put("itemClass", "FINISHED_GOOD");
    payload.put("color", "WHITE");
    payload.put("size", "1L");
    payload.put("unitOfMeasure", "LITER");
    payload.put("hsnCode", "320910");
    payload.put("gstRate", new BigDecimal("18.00"));
    payload.put("basePrice", new BigDecimal("1200.00"));
    payload.put("minDiscountPercent", new BigDecimal("5.00"));
    payload.put("minSellingPrice", new BigDecimal("1140.00"));
    payload.put("metadata", metadata);
    return payload;
  }

  private Map<String, Object> singleProductPayload(Long brandId, String customSkuCode) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("wipAccountId", wipAccount.getId());
    metadata.put("productType", "decorative");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("productName", "Single Route Primer " + shortId());
    payload.put("category", "FINISHED_GOOD");
    payload.put("defaultColour", "WHITE");
    payload.put("sizeLabel", "1L");
    payload.put("unitOfMeasure", "LITER");
    payload.put("hsnCode", "320910");
    payload.put("customSkuCode", customSkuCode);
    payload.put("basePrice", new BigDecimal("1200.00"));
    payload.put("gstRate", new BigDecimal("18.00"));
    payload.put("minDiscountPercent", new BigDecimal("5.00"));
    payload.put("minSellingPrice", new BigDecimal("1140.00"));
    payload.put("metadata", metadata);
    return payload;
  }

  private Map<String, Object> bulkVariantPayload(String brandName, String brandCode) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandName", brandName);
    payload.put("brandCode", brandCode);
    payload.put("baseProductName", "Dry Run Primer");
    payload.put("category", "FINISHED_GOOD");
    payload.put("colors", List.of("WHITE", "BLUE"));
    payload.put(
        "colorSizeMatrix",
        List.of(
            Map.of("color", "WHITE", "sizes", List.of("1L", "4L")),
            Map.of("color", "BLUE", "sizes", List.of("10L"))));
    payload.put("unitOfMeasure", "LITER");
    payload.put("skuPrefix", "DRYRUN");
    payload.put("basePrice", new BigDecimal("1500.00"));
    payload.put("gstRate", new BigDecimal("18.00"));
    payload.put("minDiscountPercent", new BigDecimal("4.00"));
    payload.put("minSellingPrice", new BigDecimal("1380.00"));
    payload.put("metadata", Map.of("productType", "decorative"));
    return payload;
  }

  private Map<String, Object> salesOrderPayload(String sku) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("productCode", sku);
    item.put("description", "Catalog public surface flow");
    item.put("quantity", BigDecimal.ONE);
    item.put("unitPrice", new BigDecimal("1200.00"));
    item.put("gstRate", new BigDecimal("18.00"));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("totalAmount", new BigDecimal("1416.00"));
    payload.put("currency", "INR");
    payload.put("notes", "catalog public surface retirement");
    payload.put("items", List.of(item));
    payload.put("gstTreatment", "PER_ITEM");
    payload.put("gstInclusive", false);
    payload.put("paymentMode", "CASH");
    return payload;
  }

  private Map<String, Object> productionLogPayload(
      Long brandId, Long productId, Long rawMaterialId) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("rawMaterialId", rawMaterialId);
    material.put("quantity", new BigDecimal("2.50"));
    material.put("unitOfMeasure", "KG");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("productId", productId);
    payload.put("batchColour", "WHITE");
    payload.put("batchSize", new BigDecimal("10.00"));
    payload.put("unitOfMeasure", "LITER");
    payload.put("mixedQuantity", new BigDecimal("10.00"));
    payload.put("createdBy", "catalog-public-surface-retirement");
    payload.put("laborCost", BigDecimal.ZERO);
    payload.put("overheadCost", BigDecimal.ZERO);
    payload.put("materials", List.of(material));
    return payload;
  }

  private RawMaterial createRawMaterialWithBatch(String sku, String name, BigDecimal stock) {
    RawMaterial material = new RawMaterial();
    material.setCompany(company);
    material.setSku(sku);
    material.setName(name);
    material.setUnitType("KG");
    material.setCurrentStock(stock);
    material.setInventoryAccountId(inventoryAccount.getId());
    material.setGstRate(new BigDecimal("5.00"));
    RawMaterial saved = rawMaterialRepository.save(material);

    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(saved);
    batch.setBatchCode("BATCH-" + sku);
    batch.setQuantity(stock);
    batch.setUnit("KG");
    batch.setCostPerUnit(new BigDecimal("25.00"));
    batch.setReceivedAt(Instant.now());
    rawMaterialBatchRepository.save(batch);
    return saved;
  }

  private ResponseEntity<Map> searchItemsWithStock(HttpHeaders requestHeaders, String sku) {
    return rest.exchange(
        "/api/v1/catalog/items?q=" + sku + "&includeStock=true",
        HttpMethod.GET,
        new HttpEntity<>(requestHeaders),
        Map.class);
  }

  private ResponseEntity<Map> getItemWithStock(HttpHeaders requestHeaders, Long productId) {
    return rest.exchange(
        "/api/v1/catalog/items/" + productId + "?includeStock=true",
        HttpMethod.GET,
        new HttpEntity<>(requestHeaders),
        Map.class);
  }

  private Map<String, Object> browseProduct(HttpHeaders requestHeaders, Long brandId, String sku) {
    ResponseEntity<Map> browseResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=" + sku + "&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(requestHeaders),
            Map.class);
    assertThat(browseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return browseProduct(browseResponse, sku);
  }

  private Map<String, Object> browseProduct(ResponseEntity<Map> response, String sku) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> browseContent =
        (List<Map<String, Object>>) data(response).get("content");
    return browseContent.stream()
        .filter(candidate -> sku.equals(String.valueOf(candidate.get("code"))))
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private List<String> readinessBlockers(Map<String, Object> product, String stage) {
    Map<String, Object> readiness = (Map<String, Object>) product.get("readiness");
    assertThat(readiness).isNotNull();
    Map<String, Object> stageData = (Map<String, Object>) readiness.get(stage);
    assertThat(stageData).isNotNull();
    return (List<String>) stageData.get("blockers");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (List<Map<String, Object>>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> dataListMap(ResponseEntity<Map> response, String key) {
    assertThat(response.getBody()).isNotNull();
    return (List<Map<String, Object>>) data(response).get(key);
  }

  private String shortId() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  private record DownstreamFlowResult(String sku, Long productId, Long brandId) {}
}
