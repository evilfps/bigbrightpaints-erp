package com.bigbrightpaints.erp.modules.inventory;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    void raw_material_update_propagates_to_linked_product() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("name", "RM Titanium Oxide");
        createPayload.put("sku", "RM-TIO2-001");
        createPayload.put("unitType", "KG");
        createPayload.put("reorderLevel", new BigDecimal("10"));
        createPayload.put("minStock", new BigDecimal("20"));
        createPayload.put("maxStock", new BigDecimal("200"));
        createPayload.put("inventoryAccountId", accounts.get("INV"));

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/accounting/raw-materials",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long rawMaterialId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("name", "RM Titanium Oxide Updated");
        updatePayload.put("sku", "RM-TIO2-001");
        updatePayload.put("unitType", "KG");
        updatePayload.put("reorderLevel", new BigDecimal("15"));
        updatePayload.put("minStock", new BigDecimal("30"));
        updatePayload.put("maxStock", new BigDecimal("250"));
        updatePayload.put("inventoryAccountId", accounts.get("INV"));
        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/accounting/raw-materials/" + rawMaterialId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updateData = (Map<?, ?>) update.getBody().get("data");
        assertThat(updateData.get("name")).isEqualTo("RM Titanium Oxide Updated");

        RawMaterial updated = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
        assertThat(updated.getName()).isEqualTo("RM Titanium Oxide Updated");
        assertThat(updated.getReorderLevel()).isEqualByComparingTo("15");

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        ProductionProduct linked = productionProductRepository.findByCompanyAndSkuCode(company, "RM-TIO2-001")
                .orElseThrow();
        assertThat(linked.getProductName()).isEqualTo("RM Titanium Oxide Updated");
        assertThat(linked.getUnitOfMeasure()).isEqualTo("KG");
    }

    @Test
    void raw_material_create_normalizes_weighted_average_alias_under_turkish_locale() {
        HttpHeaders headers = authenticatedHeaders();
        Map<String, Long> accounts = fixtureAccountIds();

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", "RM Locale WAC");
            payload.put("sku", "RM-LOC-" + UUID.randomUUID().toString().substring(0, 8));
            payload.put("unitType", "KG");
            payload.put("reorderLevel", new BigDecimal("10"));
            payload.put("minStock", new BigDecimal("20"));
            payload.put("maxStock", new BigDecimal("200"));
            payload.put("inventoryAccountId", accounts.get("INV"));
            payload.put("costingMethod", " weighted-average ");

            ResponseEntity<Map> create = rest.exchange(
                    "/api/v1/accounting/raw-materials",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    Map.class
            );

            assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<?, ?> data = (Map<?, ?>) create.getBody().get("data");
            assertThat(data.get("costingMethod")).isEqualTo("WAC");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void production_catalog_update_adjusts_price_and_name() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Map<String, Object> metadata = Map.of(
                "fgValuationAccountId", accounts.get("INV"),
                "fgCogsAccountId", accounts.get("COGS"),
                "fgRevenueAccountId", accounts.get("REV"),
                "fgDiscountAccountId", accounts.get("DISC"),
                "fgTaxAccountId", accounts.get("GST_OUT")
        );

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productName", "Primer Base");
        createPayload.put("brandName", "HouseBrand");
        createPayload.put("category", "FINISHED_GOOD");
        createPayload.put("defaultColour", "WHITE");
        createPayload.put("sizeLabel", "1L");
        createPayload.put("unitOfMeasure", "LTR");
        createPayload.put("basePrice", new BigDecimal("125.50"));
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", new BigDecimal("110.00"));
        createPayload.put("metadata", metadata);

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/accounting/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdData = (Map<?, ?>) create.getBody().get("data");
        Long productId = ((Number) createdData.get("id")).longValue();

        Map<String, Object> updatePayload = Map.of(
                "productName", "Primer Base Plus",
                "basePrice", new BigDecimal("150.00"),
                "minSellingPrice", new BigDecimal("130.00")
        );

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/accounting/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class
        );
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updateData = (Map<?, ?>) update.getBody().get("data");
        assertThat(updateData.get("productName")).isEqualTo("Primer Base Plus");
        assertThat(new BigDecimal(updateData.get("basePrice").toString()))
                .isEqualByComparingTo("150.00");

        ProductionProduct saved = productionProductRepository.findById(productId).orElseThrow();
        assertThat(saved.getProductName()).isEqualTo("Primer Base Plus");
        assertThat(saved.getBasePrice()).isEqualByComparingTo("150.00");
        assertThat(saved.getMinSellingPrice()).isEqualByComparingTo("130.00");
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

    @Test
    void create_product_rejects_multi_value_color_and_size_and_points_to_bulk_variants() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Map<String, Object> metadata = Map.of(
                "fgValuationAccountId", accounts.get("INV"),
                "fgCogsAccountId", accounts.get("COGS"),
                "fgRevenueAccountId", accounts.get("REV"),
                "fgDiscountAccountId", accounts.get("DISC"),
                "fgTaxAccountId", accounts.get("GST_OUT")
        );

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productName", "Primer QuickAdd " + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("brandName", "HouseBrand");
        createPayload.put("category", "FINISHED_GOOD");
        createPayload.put("defaultColour", "RED,BLUE");
        createPayload.put("sizeLabel", "1L,2L");
        createPayload.put("unitOfMeasure", "LTR");
        createPayload.put("basePrice", new BigDecimal("125.50"));
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", new BigDecimal("110.00"));
        createPayload.put("metadata", metadata);

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/accounting/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> errorData = (Map<?, ?>) create.getBody().get("data");
        assertThat(String.valueOf(errorData.get("message")))
                .contains("/api/v1/accounting/catalog/products/bulk-variants");
    }

    @Test
    void create_product_rejects_multi_value_size_from_unit_fallback_and_points_to_bulk_variants() {
        HttpHeaders headers = authenticatedHeaders();

        Map<String, Long> accounts = fixtureAccountIds();
        Map<String, Object> metadata = Map.of(
                "fgValuationAccountId", accounts.get("INV"),
                "fgCogsAccountId", accounts.get("COGS"),
                "fgRevenueAccountId", accounts.get("REV"),
                "fgDiscountAccountId", accounts.get("DISC"),
                "fgTaxAccountId", accounts.get("GST_OUT")
        );

        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("productName", "Primer Fallback " + UUID.randomUUID().toString().substring(0, 8));
        createPayload.put("brandName", "HouseBrand");
        createPayload.put("category", "FINISHED_GOOD");
        createPayload.put("defaultColour", "RED");
        createPayload.put("unitOfMeasure", "1L,2L");
        createPayload.put("basePrice", new BigDecimal("125.50"));
        createPayload.put("gstRate", BigDecimal.ZERO);
        createPayload.put("minDiscountPercent", BigDecimal.ZERO);
        createPayload.put("minSellingPrice", new BigDecimal("110.00"));
        createPayload.put("metadata", metadata);

        ResponseEntity<Map> create = rest.exchange(
                "/api/v1/accounting/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class
        );

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> errorData = (Map<?, ?>) create.getBody().get("data");
        assertThat(String.valueOf(errorData.get("message")))
                .contains("/api/v1/accounting/catalog/products/bulk-variants");
    }

    @Test
    void bulk_variants_expand_comma_separated_colors_and_sizes() {
        HttpHeaders headers = authenticatedHeaders();

        String baseProductName = "Primer Variant " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = new HashMap<>();
        payload.put("brandName", "HouseBrand");
        payload.put("baseProductName", baseProductName);
        payload.put("category", "FINISHED_GOOD");
        payload.put("colors", List.of("Red, Blue", "blue", "Green"));
        payload.put("sizes", List.of("1L,2L", "2L"));
        payload.put("skuPrefix", "HB");
        payload.put("unitOfMeasure", "LTR");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/catalog/products/bulk-variants",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(((Number) data.get("created")).intValue()).isEqualTo(6);
        assertThat(((Number) data.get("skippedExisting")).intValue()).isZero();
        assertThat((List<?>) data.get("variants")).hasSize(6);

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Map<String, Set<String>> sizesByColor = productionProductRepository.findByCompanyOrderByProductNameAsc(company).stream()
                .filter(product -> product.getProductName() != null && product.getProductName().startsWith(baseProductName + " "))
                .collect(Collectors.groupingBy(
                        product -> product.getDefaultColour().toUpperCase(Locale.ROOT),
                        Collectors.mapping(ProductionProduct::getSizeLabel, Collectors.toSet())
                ));

        assertThat(sizesByColor).containsOnlyKeys("RED", "BLUE", "GREEN");
        assertThat(sizesByColor.get("RED")).containsExactlyInAnyOrder("1L", "2L");
        assertThat(sizesByColor.get("BLUE")).containsExactlyInAnyOrder("1L", "2L");
        assertThat(sizesByColor.get("GREEN")).containsExactlyInAnyOrder("1L", "2L");
    }

    @Test
    void bulk_variants_allow_color_specific_size_overrides() {
        HttpHeaders headers = authenticatedHeaders();

        String baseProductName = "Primer Matrix " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = new HashMap<>();
        payload.put("brandName", "HouseBrand");
        payload.put("baseProductName", baseProductName);
        payload.put("category", "FINISHED_GOOD");
        payload.put("colors", List.of("Black, White, Blue"));
        payload.put("sizes", List.of("S,M,L,XL"));
        payload.put("colorSizeMatrix", List.of(
                Map.of("color", "Blue", "sizes", List.of("XS,XL")),
                Map.of("color", "White", "sizes", List.of("M"))
        ));
        payload.put("skuPrefix", "HB");
        payload.put("unitOfMeasure", "LTR");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/catalog/products/bulk-variants",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(((Number) data.get("created")).intValue()).isEqualTo(7);
        assertThat(((Number) data.get("skippedExisting")).intValue()).isZero();

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Map<String, Set<String>> sizesByColor = productionProductRepository.findByCompanyOrderByProductNameAsc(company).stream()
                .filter(product -> product.getProductName() != null && product.getProductName().startsWith(baseProductName + " "))
                .collect(Collectors.groupingBy(
                        product -> product.getDefaultColour().toUpperCase(Locale.ROOT),
                        Collectors.mapping(ProductionProduct::getSizeLabel, Collectors.toSet())
                ));

        assertThat(sizesByColor).containsOnlyKeys("BLACK", "WHITE", "BLUE");
        assertThat(sizesByColor.get("BLACK")).containsExactlyInAnyOrder("S", "M", "L", "XL");
        assertThat(sizesByColor.get("WHITE")).containsExactly("M");
        assertThat(sizesByColor.get("BLUE")).containsExactlyInAnyOrder("XS", "XL");
    }

    @Test
    void bulk_variants_accept_matrix_only_colors_and_sizes() {
        HttpHeaders headers = authenticatedHeaders();

        String baseProductName = "Primer MatrixOnly " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = new HashMap<>();
        payload.put("brandName", "HouseBrand");
        payload.put("baseProductName", baseProductName);
        payload.put("category", "FINISHED_GOOD");
        payload.put("colors", List.of());
        payload.put("sizes", List.of());
        payload.put("colorSizeMatrix", List.of(
                Map.of("color", "Amber, Teal", "sizes", List.of("250ML,500ML"))
        ));
        payload.put("skuPrefix", "HB");
        payload.put("unitOfMeasure", "LTR");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/catalog/products/bulk-variants",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(((Number) data.get("created")).intValue()).isEqualTo(4);
        assertThat(((Number) data.get("skippedExisting")).intValue()).isZero();

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Map<String, Set<String>> sizesByColor = productionProductRepository.findByCompanyOrderByProductNameAsc(company).stream()
                .filter(product -> product.getProductName() != null && product.getProductName().startsWith(baseProductName + " "))
                .collect(Collectors.groupingBy(
                        product -> product.getDefaultColour().toUpperCase(Locale.ROOT),
                        Collectors.mapping(ProductionProduct::getSizeLabel, Collectors.toSet())
                ));

        assertThat(sizesByColor).containsOnlyKeys("AMBER", "TEAL");
        assertThat(sizesByColor.get("AMBER")).containsExactlyInAnyOrder("250ML", "500ML");
        assertThat(sizesByColor.get("TEAL")).containsExactlyInAnyOrder("250ML", "500ML");
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
        headers.add("X-Company-Id", COMPANY_CODE);
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
