package com.bigbrightpaints.erp.modules.inventory;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class RawMaterialAndProductUpdateIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "catalog-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private ProductionProductRepository productionProductRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Catalog Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        ensureCompanyDefaults();
    }

    @Test
    void catalog_raw_material_item_class_creates_linked_material_and_product() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Long brandId = ensureCatalogBrand(headers, "RM Host Brand");
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("brandId", brandId);
        createPayload.put("name", "Titanium Oxide");
        createPayload.put("itemClass", "RAW_MATERIAL");
        createPayload.put("color", "BASE");
        createPayload.put("size", "STD");
        createPayload.put("unitOfMeasure", "KG");
        createPayload.put("hsnCode", "320910");
        createPayload.put("basePrice", BigDecimal.ZERO);
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", BigDecimal.ZERO);
        createPayload.put("metadata", Map.of("inventoryAccountId", accounts.get("INV")));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = responseData(create);
        String sku = String.valueOf(createdData.get("code"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        RawMaterial rawMaterial = rawMaterialRepository.findByCompanyAndSku(company, sku).orElseThrow();
        ProductionProduct linked = productionProductRepository.findByCompanyAndSkuCode(company, sku).orElseThrow();

        assertThat(sku).startsWith("RM-");
        assertThat(((Number) createdData.get("rawMaterialId")).longValue()).isEqualTo(rawMaterial.getId());
        assertThat(rawMaterial.getName()).isEqualTo("Titanium Oxide BASE");
        assertThat(rawMaterial.getInventoryAccountId()).isEqualTo(accounts.get("INV"));
        assertThat(linked.getProductName()).isEqualTo(rawMaterial.getName());
        assertThat(linked.getCategory()).isEqualTo("RAW_MATERIAL");
        assertThat(linked.getSizeLabel()).isEqualTo("STD");
    }

    @Test
    void catalog_packaging_item_class_creates_packaging_material() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Long brandId = ensureCatalogBrand(headers, "Packaging Host Brand");
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("brandId", brandId);
        createPayload.put("name", "Plastic Bucket");
        createPayload.put("itemClass", "PACKAGING_RAW_MATERIAL");
        createPayload.put("size", "1L");
        createPayload.put("unitOfMeasure", "UNIT");
        createPayload.put("hsnCode", "392310");
        createPayload.put("basePrice", BigDecimal.ZERO);
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", BigDecimal.ZERO);
        createPayload.put("metadata", Map.of("inventoryAccountId", accounts.get("INV")));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = responseData(create);
        String sku = String.valueOf(createdData.get("code"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        RawMaterial rawMaterial = rawMaterialRepository.findByCompanyAndSku(company, sku).orElseThrow();

        assertThat(sku).startsWith("PKG-");
        assertThat(((Number) createdData.get("rawMaterialId")).longValue()).isEqualTo(rawMaterial.getId());
        assertThat(rawMaterial.getMaterialType().name()).isEqualTo("PACKAGING");
    }

    @Test
    void catalog_product_update_rejects_reclassifying_raw_material_to_packaging() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Long brandId = ensureCatalogBrand(headers, "Reclass Brand");
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("brandId", brandId);
        createPayload.put("name", "Bucket Shell");
        createPayload.put("itemClass", "RAW_MATERIAL");
        createPayload.put("color", "WHITE");
        createPayload.put("size", "1L");
        createPayload.put("unitOfMeasure", "UNIT");
        createPayload.put("hsnCode", "392310");
        createPayload.put("basePrice", BigDecimal.ZERO);
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", BigDecimal.ZERO);
        createPayload.put("metadata", Map.of("inventoryAccountId", accounts.get("INV")));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = responseData(create);
        Long productId = ((Number) createdData.get("id")).longValue();
        Long rawMaterialId = ((Number) createdData.get("rawMaterialId")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("brandId", brandId);
        updatePayload.put("name", "Bucket Shell");
        updatePayload.put("itemClass", "PACKAGING_RAW_MATERIAL");
        updatePayload.put("color", "WHITE");
        updatePayload.put("size", "1L");
        updatePayload.put("unitOfMeasure", "UNIT");
        updatePayload.put("hsnCode", "392310");
        updatePayload.put("basePrice", BigDecimal.ZERO);
        updatePayload.put("gstRate", BigDecimal.ZERO);
        updatePayload.put("minDiscountPercent", BigDecimal.ZERO);
        updatePayload.put("minSellingPrice", BigDecimal.ZERO);
        updatePayload.put("active", true);

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/catalog/items/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );

        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(update.getBody())).contains("itemClass is immutable");

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        RawMaterial updatedMaterial = rawMaterialRepository.findByCompanyAndId(company, rawMaterialId).orElseThrow();
        assertThat(updatedMaterial.getMaterialType().name()).isEqualTo("PRODUCTION");
    }

    @Test
    void catalog_raw_material_item_class_rejects_malformed_costing_token() {
        HttpHeaders headers = authenticatedHeaders();
        Map<String, Long> accounts = fixtureAccountIds();
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        long beforeCount = rawMaterialRepository.findByCompanyOrderByNameAsc(company).size();

        Map<String, Object> payload = new HashMap<>();
        payload.put("brandId", ensureCatalogBrand(headers, "RM Invalid Brand"));
        payload.put("name", "Bad Raw Material");
        payload.put("itemClass", "RAW_MATERIAL");
        payload.put("color", "BASE");
        payload.put("size", "STD");
        payload.put("unitOfMeasure", "KG");
        payload.put("hsnCode", "320910");
        payload.put("basePrice", BigDecimal.ZERO);
        payload.put("gstRate", BigDecimal.ZERO);
        payload.put("minDiscountPercent", BigDecimal.ZERO);
        payload.put("minSellingPrice", BigDecimal.ZERO);
        payload.put("metadata", Map.of(
                "inventoryAccountId", accounts.get("INV"),
                "costingMethod", "WAC;DROP"
        ));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        long afterCount = rawMaterialRepository.findByCompanyOrderByNameAsc(company).size();
        assertThat(afterCount).isEqualTo(beforeCount + 1);
    }

    @Test
    void catalog_product_update_uses_canonical_host_and_pre_resolved_brand_id() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Map<String, Object> metadata = Map.of(
                "fgValuationAccountId", accounts.get("INV"),
                "fgCogsAccountId", accounts.get("COGS"),
                "fgRevenueAccountId", accounts.get("REV"),
                "fgDiscountAccountId", accounts.get("DISC"),
                "fgTaxAccountId", accounts.get("GST_OUT")
        );

        ResponseEntity<Map> brandCreate = rest.exchange(
                "/api/v1/catalog/brands",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "HouseBrand Canonical"), headers),
                Map.class
        );
        assertThat(brandCreate.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long brandId = ((Number) ((Map<?, ?>) brandCreate.getBody().get("data")).get("id")).longValue();

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("brandId", brandId);
        createPayload.put("name", "Primer Base");
        createPayload.put("itemClass", "FINISHED_GOOD");
        createPayload.put("color", "WHITE");
        createPayload.put("size", "1L");
        createPayload.put("unitOfMeasure", "LITER");
        createPayload.put("hsnCode", "320910");
        createPayload.put("basePrice", new BigDecimal("125.50"));
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", new BigDecimal("110.00"));
        createPayload.put("metadata", metadata);

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = responseData(create);
        Long productId = ((Number) createdData.get("id")).longValue();
        String originalCode = String.valueOf(createdData.get("code"));

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("brandId", brandId);
        updatePayload.put("name", "Primer Base Plus");
        updatePayload.put("itemClass", "FINISHED_GOOD");
        updatePayload.put("color", "WHITE");
        updatePayload.put("size", "1L");
        updatePayload.put("unitOfMeasure", "LITER");
        updatePayload.put("hsnCode", "320910");
        updatePayload.put("basePrice", new BigDecimal("125.50"));
        updatePayload.put("gstRate", BigDecimal.ZERO);
        updatePayload.put("minDiscountPercent", BigDecimal.ZERO);
        updatePayload.put("minSellingPrice", new BigDecimal("110.00"));
        updatePayload.put("active", true);

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/catalog/items/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updateData = responseData(update);
        assertThat(updateData.get("name")).isEqualTo("Primer Base Plus WHITE 1L");
        assertThat(updateData.get("code")).isEqualTo(originalCode);

        ProductionProduct saved = productionProductRepository.findById(productId).orElseThrow();
        assertThat(saved.getProductName()).isEqualTo("Primer Base Plus WHITE 1L");
        assertThat(saved.getDefaultColour()).isEqualTo("WHITE");
        assertThat(saved.getSizeLabel()).isEqualTo("1L");
    }

    @Test
    void finished_good_write_routes_are_retired_for_create_and_update() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", "FG-RET-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("name", "FG Retired Write");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        FinishedGood existing = new FinishedGood();
        existing.setCompany(company);
        existing.setProductCode("FG-RET-UPD-" + UUID.randomUUID().toString().substring(0, 8));
        existing.setName("FG Retired Update");
        existing.setUnit("UNIT");
        existing.setCurrentStock(BigDecimal.ZERO);
        existing.setReservedStock(BigDecimal.ZERO);
        existing = finishedGoodRepository.save(existing);

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/finished-goods/" + existing.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(update.getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
    }

    @Test
    void finished_good_retired_create_route_does_not_create_side_effects() {
        HttpHeaders headers = authenticatedHeaders();
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        long beforeCount = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).size();
        String productCode = "FG-RET-NOOP-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", productCode);
        payload.put("name", "FG Retired Noop");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, productCode)).isEmpty();
        long afterCount = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).size();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    private HttpHeaders authenticatedHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Company-Code", COMPANY_CODE);
        return headers;
    }

    private Map<?, ?> fetchFinishedGoodData(HttpHeaders headers, Long finishedGoodId) {
        ResponseEntity<Map> fetch = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        assertThat(fetch.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) fetch.getBody().get("data");
    }

    private Map<String, Long> fixtureAccountIds() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        return Map.of(
                "INV", findAccountId(company, "INV"),
                "COGS", findAccountId(company, "COGS"),
                "REV", findAccountId(company, "REV"),
                "DISC", findAccountId(company, "DISC"),
                "GST_OUT", findAccountId(company, "GST-OUT")
        );
    }

    private Long ensureCatalogBrand(HttpHeaders headers, String brandName) {
        ResponseEntity<Map> brandCreate = rest.exchange(
                "/api/v1/catalog/brands",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", brandName), headers),
                Map.class
        );
        assertThat(brandCreate.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) ((Map<?, ?>) brandCreate.getBody().get("data")).get("id")).longValue();
    }

    private void ensureCompanyDefaults() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Long inv = findAccountId(company, "INV");
        Long cogs = findAccountId(company, "COGS");
        Long rev = findAccountId(company, "REV");
        Long disc = findAccountId(company, "DISC");
        Long tax = findAccountId(company, "GST-OUT");
        company.setDefaultInventoryAccountId(inv);
        company.setDefaultCogsAccountId(cogs);
        company.setDefaultRevenueAccountId(rev);
        company.setDefaultDiscountAccountId(disc);
        company.setDefaultTaxAccountId(tax);
        company.setGstOutputTaxAccountId(tax);
        companyRepository.save(company);
    }

    private Map<?, ?> responseData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<?, ?>) response.getBody().get("data");
    }

    private Long findAccountId(Company company, String code) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .map(Account::getId)
                .orElseThrow(() -> new IllegalStateException("Fixture account not found: " + code));
    }
}
