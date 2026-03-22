package com.bigbrightpaints.erp.modules.inventory;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
        createPayload.put("baseProductName", "Titanium Oxide");
        createPayload.put("category", "RAW_MATERIAL");
        createPayload.put("itemClass", "RAW_MATERIAL");
        createPayload.put("colors", List.of("BASE"));
        createPayload.put("sizes", List.of("STD"));
        createPayload.put("unitOfMeasure", "KG");
        createPayload.put("hsnCode", "320910");
        createPayload.put("basePrice", BigDecimal.ZERO);
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", BigDecimal.ZERO);
        createPayload.put("metadata", Map.of("inventoryAccountId", accounts.get("INV")));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Map<?, ?> member = (Map<?, ?>) ((List<?>) createdData.get("members")).getFirst();
        String sku = (String) member.get("sku");

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        RawMaterial rawMaterial = rawMaterialRepository.findByCompanyAndSku(company, sku).orElseThrow();
        ProductionProduct linked = productionProductRepository.findByCompanyAndSkuCode(company, sku).orElseThrow();

        assertThat(sku).startsWith("RM-");
        assertThat(rawMaterial.getName()).isEqualTo("Titanium Oxide BASE STD");
        assertThat(rawMaterial.getInventoryAccountId()).isEqualTo(accounts.get("INV"));
        assertThat(linked.getProductName()).isEqualTo(rawMaterial.getName());
        assertThat(linked.getCategory()).isEqualTo("RAW_MATERIAL");
    }

    @Test
    void catalog_packaging_item_class_creates_packaging_material() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Long brandId = ensureCatalogBrand(headers, "Packaging Host Brand");
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("brandId", brandId);
        createPayload.put("baseProductName", "Plastic Bucket");
        createPayload.put("category", "RAW_MATERIAL");
        createPayload.put("itemClass", "PACKAGING_RAW_MATERIAL");
        createPayload.put("colors", List.of("WHITE"));
        createPayload.put("sizes", List.of("1L"));
        createPayload.put("unitOfMeasure", "UNIT");
        createPayload.put("hsnCode", "392310");
        createPayload.put("basePrice", BigDecimal.ZERO);
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", BigDecimal.ZERO);
        createPayload.put("metadata", Map.of("inventoryAccountId", accounts.get("INV")));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Map<?, ?> member = (Map<?, ?>) ((List<?>) createdData.get("members")).getFirst();
        String sku = (String) member.get("sku");

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        RawMaterial rawMaterial = rawMaterialRepository.findByCompanyAndSku(company, sku).orElseThrow();

        assertThat(sku).startsWith("PKG-");
        assertThat(rawMaterial.getMaterialType().name()).isEqualTo("PACKAGING");
    }

    @Test
    void catalog_raw_material_item_class_rejects_malformed_costing_token() {
        HttpHeaders headers = authenticatedHeaders();
        Map<String, Long> accounts = fixtureAccountIds();
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        long beforeCount = rawMaterialRepository.findByCompanyOrderByNameAsc(company).size();

        Map<String, Object> payload = new HashMap<>();
        payload.put("brandId", ensureCatalogBrand(headers, "RM Invalid Brand"));
        payload.put("baseProductName", "Bad Raw Material");
        payload.put("category", "RAW_MATERIAL");
        payload.put("itemClass", "RAW_MATERIAL");
        payload.put("colors", List.of("BASE"));
        payload.put("sizes", List.of("STD"));
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
                "/api/v1/catalog/products",
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
        createPayload.put("baseProductName", "Primer Base");
        createPayload.put("category", "FINISHED_GOOD");
        createPayload.put("itemClass", "FINISHED_GOOD");
        createPayload.put("colors", List.of("WHITE"));
        createPayload.put("sizes", List.of("1L"));
        createPayload.put("unitOfMeasure", "LITER");
        createPayload.put("hsnCode", "320910");
        createPayload.put("basePrice", new BigDecimal("125.50"));
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", new BigDecimal("110.00"));
        createPayload.put("metadata", metadata);

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        List<?> members = (List<?>) createdData.get("members");
        assertThat(members).hasSize(1);
        Long productId = ((Number) ((Map<?, ?>) members.getFirst()).get("id")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("brandId", brandId);
        updatePayload.put("name", "Primer Base Plus");
        updatePayload.put("colors", List.of("BLACK"));
        updatePayload.put("sizes", List.of("4L"));
        updatePayload.put("cartonSizes", List.of(Map.of(
                "size", "4L",
                "piecesPerCarton", 1
        )));
        updatePayload.put("unitOfMeasure", "LITER");
        updatePayload.put("hsnCode", "320910");
        updatePayload.put("gstRate", BigDecimal.ZERO);
        updatePayload.put("active", true);

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updateData = (Map<?, ?>) update.getBody().get("data");
        assertThat(updateData.get("name")).isEqualTo("Primer Base Plus");
        @SuppressWarnings("unchecked")
        List<String> updatedColors = (List<String>) updateData.get("colors");
        @SuppressWarnings("unchecked")
        List<String> updatedSizes = (List<String>) updateData.get("sizes");
        assertThat(updatedColors).containsExactly("BLACK");
        assertThat(updatedSizes).containsExactly("4L");

        ProductionProduct saved = productionProductRepository.findById(productId).orElseThrow();
        assertThat(saved.getProductName()).isEqualTo("Primer Base Plus");
        assertThat(saved.getDefaultColour()).isEqualTo("BLACK");
        assertThat(saved.getSizeLabel()).isEqualTo("4L");
    }

    @Test
    void finished_good_create_normalizes_weighted_average_alias_to_wac() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", "FG-NORM-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("name", "FG Normalization");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", " weighted-average ");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) create.getBody().get("data");
        assertThat(data.get("costingMethod")).isEqualTo("WAC");
    }

    @Test
    void finished_good_create_preserves_lifo_method() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", "FG-LIFO-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("name", "FG LIFO");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", " lifo ");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) create.getBody().get("data");
        assertThat(data.get("costingMethod")).isEqualTo("LIFO");
    }

    @Test
    void finished_good_update_normalizes_weighted_average_alias_to_wac() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productCode", "FG-UPD-" + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("name", "FG Update");
        createPayload.put("unit", "UNIT");
        createPayload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long finishedGoodId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("productCode", createPayload.get("productCode"));
        updatePayload.put("name", "FG Update Normalized");
        updatePayload.put("unit", "UNIT");
        updatePayload.put("costingMethod", "weighted_average");

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );

        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updatedData = (Map<?, ?>) update.getBody().get("data");
        assertThat(updatedData.get("costingMethod")).isEqualTo("WAC");
    }

    @Test
    void finished_good_create_rejects_unsupported_costing_method() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", "FG-BAD-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("name", "FG Bad Method");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", "ABC");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(create.getBody())).contains("Unsupported costing method");
    }

    @Test
    void finished_good_create_rejects_malformed_costing_token_without_side_effects() {
        HttpHeaders headers = authenticatedHeaders();
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        long beforeCount = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).size();
        String productCode = "FG-BAD-MAL-" + UUID.randomUUID().toString().substring(0, 8);
        Company foreignCompany = dataSeeder.ensureCompany(
                "FG-FOREIGN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Finished Good Foreign Co"
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", productCode);
        payload.put("name", "FG Bad Malformed Method");
        payload.put("unit", "UNIT");
        payload.put("costingMethod", "WAC;DROP");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(create.getBody())).contains("Unsupported costing method");
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, productCode)).isEmpty();
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(foreignCompany, productCode)).isEmpty();
        long afterCount = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).size();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    @Test
    void finished_good_update_rejects_unsupported_costing_method() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productCode", "FG-UPD-BAD-" + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("name", "FG Update Bad Method");
        createPayload.put("unit", "UNIT");
        createPayload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long finishedGoodId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("productCode", createPayload.get("productCode"));
        updatePayload.put("name", "FG Update Bad Method");
        updatePayload.put("unit", "UNIT");
        updatePayload.put("costingMethod", "ABC");

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );

        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(update.getBody())).contains("Unsupported costing method");
    }

    @Test
    void finished_good_update_alias_replay_stays_canonical_wac() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productCode", "FG-ALIAS-" + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("name", "FG Alias Replay");
        createPayload.put("unit", "UNIT");
        createPayload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long finishedGoodId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("productCode", createPayload.get("productCode"));
        updatePayload.put("name", "FG Alias Replay A");
        updatePayload.put("unit", "UNIT");
        updatePayload.put("costingMethod", " weighted-average ");
        ResponseEntity<Map> updateWeightedAverage = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(updateWeightedAverage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) updateWeightedAverage.getBody().get("data")).get("costingMethod")).isEqualTo("WAC");

        updatePayload.put("name", "FG Alias Replay B");
        updatePayload.put("costingMethod", "WAC");
        ResponseEntity<Map> updateWac = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(updateWac.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) updateWac.getBody().get("data")).get("costingMethod")).isEqualTo("WAC");

        updatePayload.put("name", "FG Alias Replay C");
        updatePayload.put("costingMethod", "weighted_average");
        ResponseEntity<Map> updateWeightedUnderscore = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(updateWeightedUnderscore.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) updateWeightedUnderscore.getBody().get("data")).get("costingMethod")).isEqualTo("WAC");

        Map<?, ?> fetched = fetchFinishedGoodData(headers, finishedGoodId);
        assertThat(fetched.get("costingMethod")).isEqualTo("WAC");
    }

    @Test
    void finished_good_invalid_alias_replay_does_not_mutate_persisted_method() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productCode", "FG-IMM-" + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("name", "FG Immutable On Invalid");
        createPayload.put("unit", "UNIT");
        createPayload.put("costingMethod", "FIFO");

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long finishedGoodId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> invalidUpdatePayload = new HashMap<>();
        invalidUpdatePayload.put("productCode", createPayload.get("productCode"));
        invalidUpdatePayload.put("name", "FG Invalid Attempt A");
        invalidUpdatePayload.put("unit", "UNIT");
        invalidUpdatePayload.put("costingMethod", "NOT_A_METHOD");

        ResponseEntity<Map> invalidFirst = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(invalidUpdatePayload, headers),
                Map.class
        );
        assertThat(invalidFirst.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(invalidFirst.getBody())).contains("Unsupported costing method");

        Map<?, ?> afterFirstFailure = fetchFinishedGoodData(headers, finishedGoodId);
        assertThat(afterFirstFailure.get("costingMethod")).isEqualTo("FIFO");
        assertThat(afterFirstFailure.get("name")).isEqualTo("FG Immutable On Invalid");

        invalidUpdatePayload.put("name", "FG Invalid Attempt B");
        ResponseEntity<Map> invalidSecond = rest.exchange(
                "/api/v1/finished-goods/" + finishedGoodId,
                HttpMethod.PUT,
                new HttpEntity<>(invalidUpdatePayload, headers),
                Map.class
        );
        assertThat(invalidSecond.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(invalidSecond.getBody())).contains("Unsupported costing method");

        Map<?, ?> afterSecondFailure = fetchFinishedGoodData(headers, finishedGoodId);
        assertThat(afterSecondFailure.get("costingMethod")).isEqualTo("FIFO");
        assertThat(afterSecondFailure.get("name")).isEqualTo("FG Immutable On Invalid");
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
        companyRepository.save(company);
    }

    private Long findAccountId(Company company, String code) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .map(Account::getId)
                .orElseThrow(() -> new IllegalStateException("Fixture account not found: " + code));
    }
}
