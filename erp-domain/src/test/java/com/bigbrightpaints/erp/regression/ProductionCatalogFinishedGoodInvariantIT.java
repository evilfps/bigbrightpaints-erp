package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
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
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Regression: Catalog -> FinishedGood invariants")
@Tag("critical")
class ProductionCatalogFinishedGoodInvariantIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE_PREFIX = "LF-015";
    private static final String PASSWORD = "changeme";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;

    private final String companyCode = COMPANY_CODE_PREFIX + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private Company company;
    private Account inventoryAccount;
    private Account cogsAccount;
    private Account revenueAccount;
    private Account discountAccount;
    private Account taxAccount;
    private Account wipAccount;
    private HttpHeaders headers;
    private String adminEmail;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);

        inventoryAccount = ensureAccount("INV", "Inventory", AccountType.ASSET);
        cogsAccount = ensureAccount("COGS", "COGS", AccountType.COGS);
        revenueAccount = ensureAccount("REV", "Revenue", AccountType.REVENUE);
        discountAccount = ensureAccount("DISC", "Discount", AccountType.EXPENSE);
        taxAccount = ensureAccount("GST-OUT", "GST Output", AccountType.LIABILITY);
        wipAccount = ensureAccount("WIP", "Work In Progress", AccountType.ASSET);

        company.setDefaultInventoryAccountId(inventoryAccount.getId());
        company.setDefaultCogsAccountId(cogsAccount.getId());
        company.setDefaultRevenueAccountId(revenueAccount.getId());
        company.setDefaultDiscountAccountId(discountAccount.getId());
        company.setDefaultTaxAccountId(taxAccount.getId());
        companyRepository.save(company);

        adminEmail = "catalog-ready-" + companyCode.toLowerCase() + "@bbp.com";
        dataSeeder.ensureUser(adminEmail, PASSWORD, "Catalog Readiness Admin", companyCode, List.of("ROLE_ADMIN"));
        headers = authHeaders();
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void createProductAutoProvisionsFinishedGood() {
        String token = uniqueToken();
        String productName = "LF-015 Product " + token;
        String skuCode = "FG-LF015-" + token;
        ProductCreateRequest request = new ProductCreateRequest(
                null,
                "LF-015 Brand",
                null,
                productName,
                "FINISHED_GOOD",
                "WHITE",
                "1L",
                "UNIT",
                "320910",
                skuCode,
                new BigDecimal("100.00"),
                new BigDecimal("18.00"),
                null,
                null,
                null
        );

        ProductionProductDto product = productionCatalogService.createProduct(request);

        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, skuCode)
                .orElseThrow();

        assertThat(fg.getName()).isEqualTo(productName);
        assertThat(fg.getUnit()).isEqualTo("UNIT");
        assertThat(fg.getValuationAccountId()).isEqualTo(inventoryAccount.getId());
        assertThat(fg.getCogsAccountId()).isEqualTo(cogsAccount.getId());
        assertThat(fg.getRevenueAccountId()).isEqualTo(revenueAccount.getId());
        assertThat(fg.getTaxAccountId()).isEqualTo(taxAccount.getId());
        assertThat(fg.getCurrentStock()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fg.getReservedStock()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void updateProductSynchronizesFinishedGoodNameAndUnit() {
        String token = uniqueToken();
        String sourceName = "LF-015 Sync Product " + token;
        String updatedName = "LF-015 Sync Product Renamed " + token;
        String skuCode = "FG-LF015-SYNC-" + token;
        ProductionProductDto created = productionCatalogService.createProduct(new ProductCreateRequest(
                null,
                "LF-015 Brand",
                null,
                sourceName,
                "FINISHED_GOOD",
                "BLUE",
                "1L",
                "UNIT",
                "320910",
                skuCode,
                new BigDecimal("90.00"),
                new BigDecimal("18.00"),
                null,
                null,
                null
        ));

        productionCatalogService.updateProduct(created.id(), new ProductUpdateRequest(
                updatedName,
                null,
                null,
                null,
                "LITER",
                null,
                null,
                null,
                null,
                null
        ));

        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, skuCode)
                .orElseThrow();

        assertThat(fg.getName()).isEqualTo(updatedName);
        assertThat(fg.getUnit()).isEqualTo("LITER");
    }

    @Test
    void createProductRejectsReservedSemiFinishedSuffix() {
        String token = uniqueToken();
        ProductCreateRequest request = new ProductCreateRequest(
                null,
                "LF-015 Brand",
                null,
                "LF-015 Bulk Collision Product " + token,
                "FINISHED_GOOD",
                "WHITE",
                "1L",
                "UNIT",
                "320910",
                "FG-LF015-" + token + "-BULK",
                new BigDecimal("100.00"),
                new BigDecimal("18.00"),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> productionCatalogService.createProduct(request))
                .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void canonicalFinishedGoodCreate_keepsSalesAndFactoryReadyOnCanonicalBrowseIdentifiers() {
        ProductionBrand brand = saveBrand("LF-015 Ready " + uniqueToken(), true);

        ResponseEntity<Map> createResponse = rest.exchange(
                "/api/v1/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(canonicalFinishedGoodPayload(brand.getId()), headers),
                Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> createData = data(createResponse);
        @SuppressWarnings("unchecked")
        Map<String, Object> createdMember = ((List<Map<String, Object>>) createData.get("members")).getFirst();
        String sku = String.valueOf(createdMember.get("sku"));

        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseThrow();
        assertThat(finishedGood.getCurrentStock()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finishedGood.getReservedStock()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finishedGood.getValuationAccountId()).isEqualTo(inventoryAccount.getId());
        assertThat(finishedGood.getCogsAccountId()).isEqualTo(cogsAccount.getId());
        assertThat(finishedGood.getRevenueAccountId()).isEqualTo(revenueAccount.getId());
        assertThat(finishedGood.getDiscountAccountId()).isEqualTo(discountAccount.getId());
        assertThat(finishedGood.getTaxAccountId()).isEqualTo(taxAccount.getId());

        ResponseEntity<Map> browseResponse = rest.exchange(
                "/api/v1/catalog/products?brandId=" + brand.getId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(browseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> browseContent = (List<Map<String, Object>>) data(browseResponse).get("content");
        Map<String, Object> browsedProduct = browseContent.stream()
                .filter(candidate -> sku.equals(String.valueOf(candidate.get("sku"))))
                .findFirst()
                .orElseThrow();

        Long productId = ((Number) browsedProduct.get("id")).longValue();
        Long browsedBrandId = ((Number) browsedProduct.get("brandId")).longValue();
        assertThat(browsedProduct.get("publicId")).isNotNull();
        assertThat(browsedBrandId).isEqualTo(brand.getId());

        ResponseEntity<Map> salesOrderResponse = rest.exchange(
                "/api/v1/sales/orders",
                HttpMethod.POST,
                new HttpEntity<>(salesOrderPayload(sku), headers),
                Map.class);

        assertThat(salesOrderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(salesOrderResponse)).containsKeys("id", "orderNumber", "status");

        RawMaterial rawMaterial = createRawMaterialWithBatch(
                "RM-LF015-" + uniqueToken(),
                "LF-015 Binder",
                new BigDecimal("50.00"));

        ResponseEntity<Map> productionLogResponse = rest.exchange(
                "/api/v1/factory/production/logs",
                HttpMethod.POST,
                new HttpEntity<>(productionLogPayload(browsedBrandId, productId, rawMaterial.getId()), headers),
                Map.class);

        assertThat(productionLogResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(productionLogResponse)).containsKeys("id", "productionCode");
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku + "-BULK")).isPresent();
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
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
        brand.setCode(("LF" + uniqueToken()).substring(0, 10));
        brand.setActive(active);
        return productionBrandRepository.save(brand);
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", adminEmail,
                "password", PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = String.valueOf(loginResponse.getBody().get("accessToken"));

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(token);
        httpHeaders.set("X-Company-Code", companyCode);
        return httpHeaders;
    }

    private Map<String, Object> canonicalFinishedGoodPayload(Long brandId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("wipAccountId", wipAccount.getId());
        metadata.put("semiFinishedAccountId", inventoryAccount.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brandId", brandId);
        payload.put("baseProductName", "LF-015 Ready Primer");
        payload.put("category", "FINISHED_GOOD");
        payload.put("unitOfMeasure", "LITER");
        payload.put("hsnCode", "320910");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("1200.00"));
        payload.put("minDiscountPercent", new BigDecimal("5.00"));
        payload.put("minSellingPrice", new BigDecimal("1140.00"));
        payload.put("colors", List.of("WHITE"));
        payload.put("sizes", List.of("1L"));
        payload.put("metadata", metadata);
        return payload;
    }

    private Map<String, Object> salesOrderPayload(String sku) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productCode", sku);
        item.put("description", "Ready finished good");
        item.put("quantity", BigDecimal.ONE);
        item.put("unitPrice", new BigDecimal("1200.00"));
        item.put("gstRate", new BigDecimal("18.00"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalAmount", new BigDecimal("1416.00"));
        payload.put("currency", "INR");
        payload.put("notes", "catalog readiness");
        payload.put("items", List.of(item));
        payload.put("gstTreatment", "PER_ITEM");
        payload.put("gstInclusive", false);
        payload.put("paymentMode", "CASH");
        return payload;
    }

    private Map<String, Object> productionLogPayload(Long brandId, Long productId, Long rawMaterialId) {
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
        payload.put("createdBy", "catalog-downstream-readiness");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String uniqueToken() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
