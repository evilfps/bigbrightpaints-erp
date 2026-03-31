package com.bigbrightpaints.erp.modules.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class CatalogControllerCanonicalProductIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CAT-CANONICAL";
  private static final String PASSWORD = "changeme";
  private static final String ADMIN_EMAIL = "catalog-canonical-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "catalog-canonical-accounting@bbp.com";
  private static final String SALES_EMAIL = "catalog-canonical-sales@bbp.com";
  private static final String FACTORY_EMAIL = "catalog-canonical-factory@bbp.com";

  @Autowired private TestRestTemplate rest;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;

  private Company company;
  private HttpHeaders adminHeaders;
  private HttpHeaders accountingHeaders;
  private HttpHeaders salesHeaders;
  private HttpHeaders factoryHeaders;
  private Long wipAccountId;
  private Long laborAppliedAccountId;
  private Long overheadAppliedAccountId;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, "Canonical Catalog Co");
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Canonical Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Canonical Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SALES_EMAIL, PASSWORD, "Canonical Sales", COMPANY_CODE, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        FACTORY_EMAIL, PASSWORD, "Canonical Factory", COMPANY_CODE, List.of("ROLE_FACTORY"));
    configureDefaultAccounts();
    wipAccountId = ensureAccount("WIP-" + shortId(), "WIP", AccountType.ASSET).getId();
    laborAppliedAccountId =
        ensureAccount("LAB-" + shortId(), "Labor Applied", AccountType.ASSET).getId();
    overheadAppliedAccountId =
        ensureAccount("OVH-" + shortId(), "Overhead Applied", AccountType.ASSET).getId();
    adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE);
    accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
    salesHeaders = authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE);
    factoryHeaders = authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE);
  }

  @Test
  void createItem_persistsFinishedGood_withStableCode_andComputedReadiness() {
    ProductionBrand brand = saveBrand("Premium Paints", true);

    ResponseEntity<Map> createResponse =
        postCatalogItem(finishedGoodPayload(brand.getId(), "Premium Primer"), adminHeaders);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> item = data(createResponse);
    assertThat(item.get("itemClass")).isEqualTo("FINISHED_GOOD");
    assertThat(item.get("name")).isEqualTo("Premium Primer WHITE 1L");
    assertThat(item.get("code")).isEqualTo("FG-" + brand.getCode() + "-PREMIUMPRIMER-WHITE-1L");
    assertThat(stock(item))
        .containsEntry("onHandQuantity", 0)
        .containsEntry("availableQuantity", 0);
    assertThat(stage(item, "masterReady").get("ready")).isEqualTo(true);
    assertThat(stage(item, "inventoryReady").get("ready")).isEqualTo(true);
    assertThat(stage(item, "productionReady").get("ready")).isEqualTo(true);
    assertThat(stringList(stage(item, "packingReady").get("blockers")))
        .contains("PACKAGING_MAPPING_MISSING");
    assertThat(stringList(stage(item, "salesReady").get("blockers")))
        .contains("NO_FINISHED_GOOD_BATCH_STOCK");
    assertThat(stage(item, "accountingReady").get("ready")).isEqualTo(true);

    ResponseEntity<Map> salesDetail =
        getCatalogItem(((Number) item.get("id")).longValue(), true, true, salesHeaders);
    assertThat(salesDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(salesDetail).get("stock")).isNull();
    assertThat(metadata(data(salesDetail)))
        .doesNotContainKeys(
            "wipAccountId",
            "laborAppliedAccountId",
            "overheadAppliedAccountId",
            "fgValuationAccountId",
            "fgCogsAccountId",
            "fgRevenueAccountId",
            "fgTaxAccountId");
  }

  @Test
  void createItem_persistsPackagingRawMaterial_withoutNameOrSkuInference() {
    ProductionBrand brand = saveBrand("Packaging Brand", true);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brand.getId());
    payload.put("name", "Plastic Bucket");
    payload.put("itemClass", "PACKAGING_RAW_MATERIAL");
    payload.put("size", "1L");
    payload.put("unitOfMeasure", "UNIT");
    payload.put("hsnCode", "392310");
    payload.put("basePrice", BigDecimal.ZERO);
    payload.put("gstRate", BigDecimal.ZERO);
    payload.put("minDiscountPercent", BigDecimal.ZERO);
    payload.put("minSellingPrice", BigDecimal.ZERO);
    payload.put("metadata", Map.of("inventoryAccountId", company.getDefaultInventoryAccountId()));

    ResponseEntity<Map> response = postCatalogItem(payload, adminHeaders);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> item = data(response);
    assertThat(item.get("code")).isEqualTo("PKG-" + brand.getCode() + "-PLASTICBUCKET-1L-UNIT");
    assertThat(item.get("name")).isEqualTo("Plastic Bucket 1L");
    Long rawMaterialId = ((Number) item.get("rawMaterialId")).longValue();
    RawMaterial rawMaterial = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    assertThat(rawMaterial.getMaterialType().name()).isEqualTo("PACKAGING");
    assertThat(stage(item, "packingReady").get("ready")).isEqualTo(true);
  }

  @Test
  void searchItems_filtersByItemClass_andIncludesStockAndReadiness() {
    ProductionBrand brand = saveBrand("Search Brand", true);
    postCatalogItem(finishedGoodPayload(brand.getId(), "Search Paint"), adminHeaders);
    Map<String, Object> rawMaterialPayload =
        rawMaterialPayload(brand.getId(), "Titanium Dioxide", "RUTILE");
    rawMaterialPayload.put("active", false);
    postCatalogItem(rawMaterialPayload, adminHeaders);

    ResponseEntity<Map> searchResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=titanium&itemClass=RAW_MATERIAL&includeStock=true&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    ResponseEntity<Map> salesSearchResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=titanium&itemClass=RAW_MATERIAL&includeStock=true&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(salesHeaders),
            Map.class);

    assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> content = pageContent(searchResponse);
    assertThat(content).hasSize(1);
    Map<String, Object> item = content.getFirst();
    assertThat(item.get("active")).isEqualTo(false);
    assertThat(item.get("itemClass")).isEqualTo("RAW_MATERIAL");
    assertThat(item.get("name")).isEqualTo("Titanium Dioxide RUTILE");
    assertThat(item.get("code")).isEqualTo("RM-" + brand.getCode() + "-TITANIUMDIOXIDE-RUTILE-KG");
    assertThat(item).containsKey("stock");
    assertThat(item).containsKey("readiness");

    assertThat(salesSearchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> salesContent = pageContent(salesSearchResponse);
    assertThat(salesContent).hasSize(1);
    assertThat(salesContent.getFirst().get("stock")).isNull();
    assertThat(salesContent.getFirst()).containsKey("readiness");
  }

  @Test
  void getItem_includesStockForFactoryRoleWhenRequested() {
    ProductionBrand brand = saveBrand("Factory Stock Brand", true);
    Map<String, Object> created =
        data(postCatalogItem(finishedGoodPayload(brand.getId(), "Factory Stock Paint"), adminHeaders));
    Long itemId = ((Number) created.get("id")).longValue();

    ResponseEntity<Map> factoryDetail = getCatalogItem(itemId, true, true, factoryHeaders);

    assertThat(factoryDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) stock(data(factoryDetail)).get("onHandQuantity")).doubleValue()).isZero();
    assertThat(((Number) stock(data(factoryDetail)).get("availableQuantity")).doubleValue()).isZero();
  }

  @Test
  void canViewStock_handlesMissingAuthorities_andAllowsAdminAccountingAndFactoryOnly() {
    CatalogController controller = new CatalogController(null, null);
    Authentication authoritiesMissing = mock(Authentication.class);
    Authentication admin =
        new UsernamePasswordAuthenticationToken(
            "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    Authentication accounting =
        new UsernamePasswordAuthenticationToken(
            "accounting", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTING")));
    Authentication sales =
        new UsernamePasswordAuthenticationToken(
            "sales", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SALES")));
    when(authoritiesMissing.getAuthorities()).thenReturn(null);

    assertThat((Boolean) ReflectionTestUtils.invokeMethod(controller, "canViewStock", new Object[] {null}))
        .isFalse();
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(controller, "canViewStock", authoritiesMissing))
        .isFalse();
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(controller, "canViewStock", admin)).isTrue();
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(controller, "canViewStock", accounting))
        .isTrue();
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(controller, "canViewStock", sales)).isFalse();
  }

  @Test
  void updateItem_rejectsImmutableIdentityChanges() {
    ProductionBrand brand = saveBrand("Immutable Brand", true);
    Map<String, Object> created =
        data(postCatalogItem(finishedGoodPayload(brand.getId(), "Immutable Paint"), adminHeaders));
    Long itemId = ((Number) created.get("id")).longValue();

    Map<String, Object> payload = finishedGoodPayload(brand.getId(), "Immutable Paint");
    payload.put("itemClass", "RAW_MATERIAL");

    ResponseEntity<Map> updateResponse = putCatalogItem(itemId, payload, adminHeaders);
    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(String.valueOf(errorData(updateResponse).get("reason")))
        .contains("itemClass is immutable");
  }

  @Test
  void updateItem_allowsRenameAndMetadataUpdate_withoutChangingCode() {
    ProductionBrand brand = saveBrand("Update Brand", true);
    Map<String, Object> created =
        data(postCatalogItem(finishedGoodPayload(brand.getId(), "Update Paint"), adminHeaders));
    Long itemId = ((Number) created.get("id")).longValue();
    String originalCode = String.valueOf(created.get("code"));

    Map<String, Object> payload = finishedGoodPayload(brand.getId(), "Update Paint Plus");
    payload.put(
        "metadata",
        Map.of(
            "productType", "decorative",
            "wipAccountId", wipAccountId,
            "laborAppliedAccountId", laborAppliedAccountId,
            "overheadAppliedAccountId", overheadAppliedAccountId,
            "wastageAccountId", company.getDefaultCogsAccountId()));
    payload.put("basePrice", new BigDecimal("1325.00"));
    payload.put("active", true);

    ResponseEntity<Map> updateResponse = putCatalogItem(itemId, payload, adminHeaders);
    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> updated = data(updateResponse);
    assertThat(updated.get("name")).isEqualTo("Update Paint Plus WHITE 1L");
    assertThat(updated.get("code")).isEqualTo(originalCode);
    assertThat(metadata(updated))
        .containsEntry("wastageAccountId", company.getDefaultCogsAccountId().intValue());

    ResponseEntity<Map> salesDetail = getCatalogItem(itemId, true, true, salesHeaders);
    assertThat(metadata(data(salesDetail))).doesNotContainKey("wastageAccountId");
  }

  @Test
  void createAndUpdate_itemRoutes_areRestrictedToAdminAndAccounting() {
    ProductionBrand brand = saveBrand("Security Brand", true);
    Map<String, Object> payload = finishedGoodPayload(brand.getId(), "Secure Paint");

    assertThat(postCatalogItem(payload, salesHeaders).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(postCatalogItem(payload, factoryHeaders).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(postCatalogItem(payload, accountingHeaders).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    Map<String, Object> created =
        data(postCatalogItem(finishedGoodPayload(brand.getId(), "Admin Paint"), adminHeaders));
    Long itemId = ((Number) created.get("id")).longValue();
    assertThat(
            putCatalogItem(
                    itemId, finishedGoodPayload(brand.getId(), "Admin Paint Renamed"), salesHeaders)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            putCatalogItem(
                    itemId,
                    finishedGoodPayload(brand.getId(), "Admin Paint Renamed"),
                    factoryHeaders)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void importCatalog_rejectsLegacyXIdempotencyKeyHeader() {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(adminHeaders);
    headers.set("X-Idempotency-Key", "legacy-catalog-import-key");

    ResponseEntity<Map> response =
        importCatalog(
            "brand,product_name,sku,category,unit_of_measure,hsn_code,gst_rate,base_price,color,size\n"
                + "Legacy Brand,Legacy"
                + " Primer,LEGACY-PRIMER-001,FINISHED_GOOD,LITER,320910,18,1200,WHITE,1L\n",
            "catalog-import-legacy-header.csv",
            headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(String.valueOf(errorData(response).get("reason")))
        .contains("X-Idempotency-Key is not supported for catalog import");
  }

  private Map<String, Object> finishedGoodPayload(Long brandId, String name) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("name", name);
    payload.put("itemClass", "FINISHED_GOOD");
    payload.put("color", "WHITE");
    payload.put("size", "1L");
    payload.put("unitOfMeasure", "LITER");
    payload.put("hsnCode", "320910");
    payload.put("basePrice", new BigDecimal("1200.00"));
    payload.put("gstRate", new BigDecimal("18.00"));
    payload.put("minDiscountPercent", new BigDecimal("5.00"));
    payload.put("minSellingPrice", new BigDecimal("1140.00"));
    payload.put(
        "metadata",
        Map.of(
            "productType", "decorative",
            "wipAccountId", wipAccountId,
            "laborAppliedAccountId", laborAppliedAccountId,
            "overheadAppliedAccountId", overheadAppliedAccountId));
    payload.put("active", true);
    return payload;
  }

  private Map<String, Object> rawMaterialPayload(Long brandId, String name, String spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("name", name);
    payload.put("itemClass", "RAW_MATERIAL");
    payload.put("color", spec);
    payload.put("unitOfMeasure", "KG");
    payload.put("hsnCode", "320611");
    payload.put("basePrice", BigDecimal.ZERO);
    payload.put("gstRate", BigDecimal.ZERO);
    payload.put("minDiscountPercent", BigDecimal.ZERO);
    payload.put("minSellingPrice", BigDecimal.ZERO);
    payload.put("metadata", Map.of("inventoryAccountId", company.getDefaultInventoryAccountId()));
    payload.put("active", true);
    return payload;
  }

  private ResponseEntity<Map> postCatalogItem(
      Map<String, Object> payload, HttpHeaders requestHeaders) {
    return rest.exchange(
        "/api/v1/catalog/items",
        HttpMethod.POST,
        new HttpEntity<>(payload, requestHeaders),
        Map.class);
  }

  private ResponseEntity<Map> putCatalogItem(
      Long itemId, Map<String, Object> payload, HttpHeaders requestHeaders) {
    return rest.exchange(
        "/api/v1/catalog/items/" + itemId,
        HttpMethod.PUT,
        new HttpEntity<>(payload, requestHeaders),
        Map.class);
  }

  private ResponseEntity<Map> getCatalogItem(
      Long itemId, boolean includeStock, boolean includeReadiness, HttpHeaders requestHeaders) {
    return rest.exchange(
        "/api/v1/catalog/items/"
            + itemId
            + "?includeStock="
            + includeStock
            + "&includeReadiness="
            + includeReadiness,
        HttpMethod.GET,
        new HttpEntity<>(requestHeaders),
        Map.class);
  }

  private ResponseEntity<Map> importCatalog(
      String csvPayload, String fileName, HttpHeaders requestHeaders) {
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

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> pageContent(ResponseEntity<Map> response) {
    return (List<Map<String, Object>>) data(response).get("content");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> stage(Map<String, Object> item, String field) {
    return (Map<String, Object>) ((Map<String, Object>) item.get("readiness")).get(field);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> stock(Map<String, Object> item) {
    return (Map<String, Object>) item.get("stock");
  }

  @SuppressWarnings("unchecked")
  private List<String> stringList(Object value) {
    return ((List<Object>) value).stream().map(String::valueOf).toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> metadata(Map<String, Object> item) {
    return (Map<String, Object>) item.get("metadata");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> errorData(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
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
    String token = String.valueOf(loginResponse.getBody().get("accessToken"));

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private void configureDefaultAccounts() {
    Account inventory = ensureAccount("INV-" + shortId(), "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount("COGS-" + shortId(), "COGS", AccountType.COGS);
    Account revenue = ensureAccount("REV-" + shortId(), "Revenue", AccountType.REVENUE);
    Account discount = ensureAccount("DISC-" + shortId(), "Discount", AccountType.EXPENSE);
    Account tax = ensureAccount("GST-" + shortId(), "GST Output", AccountType.LIABILITY);
    company.setDefaultInventoryAccountId(inventory.getId());
    company.setDefaultCogsAccountId(cogs.getId());
    company.setDefaultRevenueAccountId(revenue.getId());
    company.setDefaultDiscountAccountId(discount.getId());
    company.setDefaultTaxAccountId(tax.getId());
    company.setGstOutputTaxAccountId(tax.getId());
    company = companyRepository.save(company);
  }

  private ProductionBrand saveBrand(String name, boolean active) {
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setName(name);
    brand.setCode(("BR" + shortId()).toUpperCase());
    brand.setActive(active);
    return brandRepository.save(brand);
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

  private String shortId() {
    String value = Long.toHexString(System.nanoTime()).toUpperCase();
    return value.substring(Math.max(0, value.length() - 6));
  }
}
