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
import java.util.List;
import java.util.Map;
import java.util.HashMap;

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

    private Long findAccountId(Company company, String code) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .map(Account::getId)
                .orElseThrow(() -> new IllegalStateException("Fixture account not found: " + code));
    }
}
