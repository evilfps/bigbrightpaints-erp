package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: cross-module tenant fail-closed boundaries")
@Tag("critical")
class CrossModuleAccountingTenantFailClosedIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;

  private Company tenantACompany;
  private Company tenantBCompany;
  private HttpHeaders tenantAHeaders;
  private HttpHeaders tenantBHeaders;

  private Dealer tenantADealer;
  private Dealer tenantBDealer;
  private ProductionBrand tenantABrand;
  private ProductionBrand tenantBBrand;
  private ProductionProduct tenantAItem;
  private ProductionProduct tenantBItem;

  private Account tenantACashAccount;
  private Account tenantARevenueAccount;
  private Account tenantBCashAccount;
  private Account tenantBRevenueAccount;

  private Long tenantAJournalId;
  private Long tenantBJournalId;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String tenantACode = "XMODA-" + suffix;
    String tenantBCode = "XMODB-" + suffix;
    String tenantAAdminEmail = "xmod-admin-a-" + suffix.toLowerCase() + "@bbp.com";
    String tenantBAdminEmail = "xmod-admin-b-" + suffix.toLowerCase() + "@bbp.com";

    dataSeeder.ensureCompany(tenantACode, "Cross Module Tenant A");
    dataSeeder.ensureCompany(tenantBCode, "Cross Module Tenant B");
    dataSeeder.ensureUser(
        tenantAAdminEmail,
        PASSWORD,
        "Cross Module Tenant A Admin",
        tenantACode,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
    dataSeeder.ensureUser(
        tenantBAdminEmail,
        PASSWORD,
        "Cross Module Tenant B Admin",
        tenantBCode,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));

    tenantACompany = companyRepository.findByCodeIgnoreCase(tenantACode).orElseThrow();
    tenantBCompany = companyRepository.findByCodeIgnoreCase(tenantBCode).orElseThrow();

    tenantACashAccount =
        ensureAccount(
            tenantACompany, "XMOD-CASH-A-" + suffix, "Cross Module Cash A", AccountType.ASSET);
    tenantARevenueAccount =
        ensureAccount(
            tenantACompany, "XMOD-REV-A-" + suffix, "Cross Module Revenue A", AccountType.REVENUE);
    tenantBCashAccount =
        ensureAccount(
            tenantBCompany, "XMOD-CASH-B-" + suffix, "Cross Module Cash B", AccountType.ASSET);
    tenantBRevenueAccount =
        ensureAccount(
            tenantBCompany, "XMOD-REV-B-" + suffix, "Cross Module Revenue B", AccountType.REVENUE);

    tenantADealer =
        ensureDealer(
            tenantACompany,
            "XMOD-DEA-A-" + suffix,
            "Cross Module Dealer A",
            "xmod-dealer-a-" + suffix.toLowerCase() + "@bbp.com");
    tenantBDealer =
        ensureDealer(
            tenantBCompany,
            "XMOD-DEA-B-" + suffix,
            "Cross Module Dealer B",
            "xmod-dealer-b-" + suffix.toLowerCase() + "@bbp.com");

    tenantABrand =
        ensureBrand(
            tenantACompany, "XMBA" + suffix.substring(0, 4), "Cross Module Brand A " + suffix);
    tenantBBrand =
        ensureBrand(
            tenantBCompany, "XMBB" + suffix.substring(0, 4), "Cross Module Brand B " + suffix);

    tenantAItem =
        ensureRawMaterialItem(
            tenantACompany,
            tenantABrand,
            "XMOD-SKU-A-" + suffix,
            "Cross Module Pigment A " + suffix,
            tenantACashAccount.getId());
    tenantBItem =
        ensureRawMaterialItem(
            tenantBCompany,
            tenantBBrand,
            "XMOD-SKU-B-" + suffix,
            "Cross Module Pigment B " + suffix,
            tenantBCashAccount.getId());

    tenantAHeaders = authHeaders(tenantAAdminEmail, tenantACode);
    tenantBHeaders = authHeaders(tenantBAdminEmail, tenantBCode);

    tenantAJournalId =
        createManualJournal(
            tenantAHeaders, tenantACashAccount.getId(), tenantARevenueAccount.getId(), "XMOD-A");
    tenantBJournalId =
        createManualJournal(
            tenantBHeaders, tenantBCashAccount.getId(), tenantBRevenueAccount.getId(), "XMOD-B");
  }

  @Test
  void tenantScopedListsExcludeForeignTenantTruthAcrossSalesCatalogAndAccounting() {
    ResponseEntity<Map> dealersResponse =
        rest.exchange(
            "/api/v1/dealers", HttpMethod.GET, new HttpEntity<>(tenantAHeaders), Map.class);
    assertThat(dealersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dataList(dealersResponse))
        .extracting(row -> ((Number) row.get("id")).longValue())
        .contains(tenantADealer.getId())
        .doesNotContain(tenantBDealer.getId());

    ResponseEntity<Map> catalogItemsResponse =
        rest.exchange(
            "/api/v1/catalog/items?includeStock=false&includeReadiness=false&page=0&pageSize=50",
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertThat(catalogItemsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pageContent(catalogItemsResponse))
        .extracting(row -> ((Number) row.get("id")).longValue())
        .contains(tenantAItem.getId())
        .doesNotContain(tenantBItem.getId());

    ResponseEntity<Map> journalsResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries?page=0&size=100",
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertThat(journalsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dataList(journalsResponse))
        .extracting(row -> ((Number) row.get("id")).longValue())
        .contains(tenantAJournalId)
        .doesNotContain(tenantBJournalId);
  }

  @Test
  void tenantScopedReadsFailClosedForForeignDealerCatalogAndAccountingIdentifiers() {
    String today = LocalDate.now().toString();
    String yesterday = LocalDate.now().minusDays(1).toString();

    ResponseEntity<Map> dealerDetailResponse =
        rest.exchange(
            "/api/v1/dealers/" + tenantBDealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(dealerDetailResponse);

    ResponseEntity<Map> portalFinanceResponse =
        rest.exchange(
            "/api/v1/portal/finance/ledger?dealerId=" + tenantBDealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(portalFinanceResponse);

    ResponseEntity<Map> brandResponse =
        rest.exchange(
            "/api/v1/catalog/brands/" + tenantBBrand.getId(),
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(brandResponse);

    ResponseEntity<Map> itemResponse =
        rest.exchange(
            "/api/v1/catalog/items/"
                + tenantBItem.getId()
                + "?includeStock=false&includeReadiness=false",
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(itemResponse);

    ResponseEntity<Map> journalQueryResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries?dealerId=" + tenantBDealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(journalQueryResponse);

    ResponseEntity<Map> accountBalanceResponse =
        rest.exchange(
            "/api/v1/accounting/accounts/"
                + tenantBCashAccount.getId()
                + "/balance/as-of?date="
                + today,
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(accountBalanceResponse);

    ResponseEntity<Map> accountCompareResponse =
        rest.exchange(
            "/api/v1/accounting/accounts/"
                + tenantBCashAccount.getId()
                + "/balance/compare?from="
                + yesterday
                + "&to="
                + today,
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertFailClosed(accountCompareResponse);
  }

  @Test
  void tenantScopedWritesFailClosedForForeignDealerCatalogAndAccountingIdentifiers() {
    long tenantAProductCountBefore =
        productionProductRepository.findByCompanyOrderByProductNameAsc(tenantACompany).size();
    int tenantAJournalCountBefore = journalIdsForTenantA().size();

    ResponseEntity<Map> dealerUpdateResponse =
        rest.exchange(
            "/api/v1/dealers/" + tenantBDealer.getId(),
            HttpMethod.PUT,
            new HttpEntity<>(dealerPayload("Cross Tenant Dealer Write"), tenantAHeaders),
            Map.class);
    assertFailClosed(dealerUpdateResponse);

    ResponseEntity<Map> dealerReceiptResponse =
        rest.exchange(
            "/api/v1/accounting/receipts/dealer",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "dealerId",
                    tenantBDealer.getId(),
                    "cashAccountId",
                    tenantACashAccount.getId(),
                    "amount",
                    new BigDecimal("50.00"),
                    "referenceNumber",
                    "XMOD-FOREIGN-RECEIPT",
                    "memo",
                    "cross-tenant probe"),
                tenantAHeaders),
            Map.class);
    assertFailClosed(dealerReceiptResponse);

    ResponseEntity<Map> journalWriteResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "entryDate",
                    LocalDate.now(),
                    "memo",
                    "Cross-tenant journal write probe",
                    "lines",
                    List.of(
                        journalLine(
                            tenantACashAccount.getId(), new BigDecimal("10.00"), BigDecimal.ZERO),
                        journalLine(
                            tenantBRevenueAccount.getId(),
                            BigDecimal.ZERO,
                            new BigDecimal("10.00")))),
                tenantAHeaders),
            Map.class);
    assertFailClosed(journalWriteResponse);

    ResponseEntity<Map> brandUpdateResponse =
        rest.exchange(
            "/api/v1/catalog/brands/" + tenantBBrand.getId(),
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "name", "Cross Tenant Brand Write",
                    "description", "probe",
                    "active", true),
                tenantAHeaders),
            Map.class);
    assertFailClosed(brandUpdateResponse);

    ResponseEntity<Map> itemCreateResponse =
        rest.exchange(
            "/api/v1/catalog/items",
            HttpMethod.POST,
            new HttpEntity<>(
                catalogItemPayload(tenantBBrand.getId(), "Cross Tenant Item Create"),
                tenantAHeaders),
            Map.class);
    assertFailClosed(itemCreateResponse);

    ResponseEntity<Map> itemUpdateResponse =
        rest.exchange(
            "/api/v1/catalog/items/" + tenantBItem.getId(),
            HttpMethod.PUT,
            new HttpEntity<>(
                catalogItemPayload(tenantABrand.getId(), "Cross Tenant Item Update"),
                tenantAHeaders),
            Map.class);
    assertFailClosed(itemUpdateResponse);

    assertThat(dealerRepository.findById(tenantBDealer.getId()).orElseThrow().getName())
        .isEqualTo("Cross Module Dealer B");
    assertThat(productionBrandRepository.findById(tenantBBrand.getId()).orElseThrow().getName())
        .isEqualTo(tenantBBrand.getName());
    assertThat(
            productionProductRepository
                .findById(tenantBItem.getId())
                .orElseThrow()
                .getProductName())
        .isEqualTo(tenantBItem.getProductName());
    assertThat(
            productionProductRepository.findByCompanyOrderByProductNameAsc(tenantACompany).size())
        .isEqualTo(tenantAProductCountBefore);
    assertThat(journalIdsForTenantA()).hasSize(tenantAJournalCountBefore);
  }

  private List<Long> journalIdsForTenantA() {
    ResponseEntity<Map> journalsResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries?page=0&size=200",
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            Map.class);
    assertThat(journalsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return dataList(journalsResponse).stream()
        .map(row -> ((Number) row.get("id")).longValue())
        .toList();
  }

  private Long createManualJournal(
      HttpHeaders headers, Long debitAccountId, Long creditAccountId, String memoPrefix) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "entryDate",
                    LocalDate.now(),
                    "memo",
                    memoPrefix + " journal",
                    "lines",
                    List.of(
                        journalLine(debitAccountId, new BigDecimal("120.00"), BigDecimal.ZERO),
                        journalLine(creditAccountId, BigDecimal.ZERO, new BigDecimal("120.00")))),
                headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return ((Number) data(response).get("id")).longValue();
  }

  private Map<String, Object> journalLine(Long accountId, BigDecimal debit, BigDecimal credit) {
    return Map.of(
        "accountId", accountId,
        "description", "cross-module tenant fail-closed line",
        "debit", debit,
        "credit", credit);
  }

  private Map<String, Object> dealerPayload(String name) {
    return Map.of(
        "name",
        name,
        "companyName",
        name + " Pvt Ltd",
        "contactEmail",
        "cross-module-" + UUID.randomUUID() + "@bbp.com",
        "contactPhone",
        "9999999999",
        "address",
        "Cross Module Address",
        "creditLimit",
        new BigDecimal("250000.00"));
  }

  private Map<String, Object> catalogItemPayload(Long brandId, String name) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("inventoryAccountId", tenantACashAccount.getId());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("brandId", brandId);
    payload.put("name", name);
    payload.put("itemClass", "RAW_MATERIAL");
    payload.put("color", "NATURAL");
    payload.put("size", "25KG");
    payload.put("unitOfMeasure", "KG");
    payload.put("hsnCode", "320611");
    payload.put("basePrice", new BigDecimal("500.00"));
    payload.put("gstRate", new BigDecimal("18.00"));
    payload.put("minDiscountPercent", BigDecimal.ZERO);
    payload.put("minSellingPrice", new BigDecimal("500.00"));
    payload.put("metadata", metadata);
    payload.put("active", true);
    return payload;
  }

  private Dealer ensureDealer(Company company, String code, String name, String email) {
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(email);
    dealer.setPhone("9999999999");
    dealer.setAddress("Cross Module Test Address");
    dealer.setStatus("ACTIVE");
    dealer.setCreditLimit(new BigDecimal("250000.00"));
    return dealerRepository.saveAndFlush(dealer);
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
              return accountRepository.saveAndFlush(account);
            });
  }

  private ProductionBrand ensureBrand(Company company, String code, String name) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode(code);
              brand.setName(name);
              brand.setActive(true);
              return productionBrandRepository.saveAndFlush(brand);
            });
  }

  private ProductionProduct ensureRawMaterialItem(
      Company company,
      ProductionBrand brand,
      String skuCode,
      String productName,
      Long inventoryAccountId) {
    return productionProductRepository
        .findByCompanyAndSkuCodeIgnoreCase(company, skuCode)
        .orElseGet(
            () -> {
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setSkuCode(skuCode);
              product.setProductName(productName);
              product.setCategory("RAW_MATERIAL");
              product.setDefaultColour("NATURAL");
              product.setSizeLabel("25KG");
              product.setUnitOfMeasure("KG");
              product.setHsnCode("320611");
              product.setBasePrice(new BigDecimal("500.00"));
              product.setGstRate(new BigDecimal("18.00"));
              product.setMinDiscountPercent(BigDecimal.ZERO);
              product.setMinSellingPrice(new BigDecimal("500.00"));
              product.setActive(true);
              product.setMetadata(Map.of("inventoryAccountId", inventoryAccountId));
              return productionProductRepository.saveAndFlush(product);
            });
  }

  private HttpHeaders authHeaders(String email, String companyCode) {
    ResponseEntity<Map> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode),
            Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    headers.set("X-Company-Code", companyCode);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private void assertFailClosed(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(response.getStatusCode())
        .isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
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
  private List<Map<String, Object>> pageContent(ResponseEntity<Map> response) {
    return (List<Map<String, Object>>) data(response).get("content");
  }
}
