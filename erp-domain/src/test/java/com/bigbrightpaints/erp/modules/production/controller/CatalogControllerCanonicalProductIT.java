package com.bigbrightpaints.erp.modules.production.controller;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
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
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class CatalogControllerCanonicalProductIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CAT-CANONICAL";
    private static final String PASSWORD = "changeme";
    private static final String ADMIN_EMAIL = "catalog-canonical-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "catalog-canonical-accounting@bbp.com";
    private static final String SALES_EMAIL = "catalog-canonical-sales@bbp.com";
    private static final String FACTORY_EMAIL = "catalog-canonical-factory@bbp.com";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionBrandRepository brandRepository;
    @Autowired private ProductionProductRepository productRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;

    private Company company;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, "Canonical Catalog Co");
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Canonical Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Canonical Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
        dataSeeder.ensureUser(SALES_EMAIL, PASSWORD, "Canonical Sales", COMPANY_CODE, List.of("ROLE_SALES"));
        dataSeeder.ensureUser(FACTORY_EMAIL, PASSWORD, "Canonical Factory", COMPANY_CODE, List.of("ROLE_FACTORY"));
        configureDefaultAccounts();
        headers = authHeaders();
    }

    @Test
    void createProduct_requiresActiveBrandId_rejectsFallbackFieldsAnd_persistsSingleCanonicalSku() {
        ProductionBrand activeBrand = saveBrand("Canonical Active " + shortId(), true);
        ProductionBrand inactiveBrand = saveBrand("Canonical Inactive " + shortId(), false);
        Account wipAccount = ensureAccount("WIP-" + shortId(), "Work In Progress", AccountType.ASSET);
        HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE);
        String baseProductName = "Premium Primer";

        ResponseEntity<Map> missingBrandResponse = postCatalogProducts(basePayload(null, baseProductName), false);
        assertThat(missingBrandResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(missingBrandResponse)).containsKey("brandId");

        ResponseEntity<Map> nonexistentBrandResponse = postCatalogProducts(basePayload(999999L, baseProductName), false);
        assertThat(nonexistentBrandResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorData(nonexistentBrandResponse)).containsEntry("code", "BUS_003");

        ResponseEntity<Map> inactiveBrandResponse = postCatalogProducts(basePayload(inactiveBrand.getId(), baseProductName), false);
        assertThat(inactiveBrandResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(errorData(inactiveBrandResponse).get("reason"))).contains("inactive");

        Map<String, Object> inlineFallbackPayload = basePayload(activeBrand.getId(), baseProductName);
        inlineFallbackPayload.put("brandName", "Inline Brand");
        ResponseEntity<Map> inlineFallbackResponse = postCatalogProducts(inlineFallbackPayload, false);
        assertThat(inlineFallbackResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(errorData(inlineFallbackResponse).get("reason"))).contains("Unsupported fields");

        Map<String, Object> packedTokenPayload = basePayload(activeBrand.getId(), baseProductName);
        packedTokenPayload.put("colors", List.of("WHITE/BLACK"));
        ResponseEntity<Map> packedTokenResponse = postCatalogProducts(packedTokenPayload, false);
        assertThat(packedTokenResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(errorData(packedTokenResponse).get("reason"))).contains("packed multi-value tokens");

        ResponseEntity<Map> successResponse = postCatalogProducts(basePayload(activeBrand.getId(), baseProductName), false);
        assertThat(successResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> data = data(successResponse);
        assertThat(data).containsEntry("preview", false);
        assertThat(data).containsEntry("category", "FINISHED_GOOD");
        assertThat(data).containsEntry("unitOfMeasure", "LITER");
        assertThat(data).containsEntry("hsnCode", "320910");
        assertThat(decimalValue(data.get("basePrice"))).isEqualByComparingTo("1200.00");
        assertThat(decimalValue(data.get("gstRate"))).isEqualByComparingTo("18.00");
        assertThat(decimalValue(data.get("minDiscountPercent"))).isEqualByComparingTo("5.00");
        assertThat(decimalValue(data.get("minSellingPrice"))).isEqualByComparingTo("1140.00");
        assertThat(metadata(data)).containsEntry("productType", "decorative");

        UUID variantGroupId = UUID.fromString(String.valueOf(data.get("variantGroupId")));
        List<Map<String, Object>> members = members(data);
        assertThat(members).hasSize(1);
        Map<String, Object> member = members.getFirst();
        assertThat(member.get("id")).isNotNull();
        assertThat(member.get("publicId")).isNotNull();
        assertThat(member.get("sku")).isEqualTo(buildCanonicalSku("FINISHED_GOOD", "Premium Primer", "WHITE", "1L"));
        assertThat(member.get("productName")).isEqualTo("Premium Primer WHITE 1L");
        assertThat(productRepository.countByCompanyAndVariantGroupId(company, variantGroupId)).isEqualTo(1);
        assertThat(productRepository.findByCompanyAndSkuCode(company, String.valueOf(member.get("sku")))).isPresent();
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, String.valueOf(member.get("sku")))).isPresent();

        ResponseEntity<Map> listResponse = rest.exchange(
                "/api/v1/catalog/products?brandId=" + activeBrand.getId(),
                HttpMethod.GET,
                new HttpEntity<>(salesHeaders),
                Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> listItem = pageContent(listResponse).stream()
                .filter(item -> String.valueOf(member.get("id")).equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
        assertCatalogReadFields(listItem, variantGroupId, "Premium Primer", "WHITE", "1L");

        ResponseEntity<Map> detailResponse = rest.exchange(
                "/api/v1/catalog/products/" + member.get("id"),
                HttpMethod.GET,
                new HttpEntity<>(salesHeaders),
                Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> detailData = data(detailResponse);
        assertCatalogReadFields(detailData, variantGroupId, "Premium Primer", "WHITE", "1L");

        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("brandId", activeBrand.getId());
        updatePayload.put("name", detailData.get("name"));
        updatePayload.put("colors", detailData.get("colors"));
        updatePayload.put("sizes", detailData.get("sizes"));
        updatePayload.put("cartonSizes", detailData.get("cartonSizes"));
        updatePayload.put("unitOfMeasure", detailData.get("unitOfMeasure"));
        updatePayload.put("hsnCode", detailData.get("hsnCode"));
        updatePayload.put("basePrice", new BigDecimal("1325.00"));
        updatePayload.put("gstRate", detailData.get("gstRate"));
        updatePayload.put("minDiscountPercent", new BigDecimal("7.50"));
        updatePayload.put("minSellingPrice", new BigDecimal("1225.00"));
        updatePayload.put("metadata", Map.of(
                "productType", "decorative",
                "wipAccountId", wipAccount.getId(),
                "wastageAccountId", company.getDefaultCogsAccountId()));
        updatePayload.put("active", true);

        ResponseEntity<Map> updateResponse = rest.exchange(
                "/api/v1/catalog/products/" + member.get("id"),
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updatedProduct = data(updateResponse);
        UUID updatedVariantGroupId = UUID.fromString(String.valueOf(updatedProduct.get("variantGroupId")));
        assertThat(updatedProduct.get("name")).isEqualTo(detailData.get("name"));
        assertThat(updatedProduct.get("productFamilyName")).isEqualTo("Premium Primer");
        assertThat(updatedVariantGroupId).isEqualTo(variantGroupId);
        assertThat(decimalValue(updatedProduct.get("basePrice"))).isEqualByComparingTo("1325.00");
        assertThat(decimalValue(updatedProduct.get("minDiscountPercent"))).isEqualByComparingTo("7.50");
        assertThat(decimalValue(updatedProduct.get("minSellingPrice"))).isEqualByComparingTo("1225.00");
        Map<String, Object> updatedMetadata = metadata(updatedProduct);
        assertThat(updatedMetadata).containsEntry("productType", "decorative");
        assertThat(((Number) updatedMetadata.get("wipAccountId")).longValue()).isEqualTo(wipAccount.getId());
        assertThat(((Number) updatedMetadata.get("wastageAccountId")).longValue()).isEqualTo(company.getDefaultCogsAccountId());

        ResponseEntity<Map> updatedDetailResponse = rest.exchange(
                "/api/v1/catalog/products/" + member.get("id"),
                HttpMethod.GET,
                new HttpEntity<>(salesHeaders),
                Map.class);
        assertThat(updatedDetailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updatedDetail = data(updatedDetailResponse);
        assertThat(UUID.fromString(String.valueOf(updatedDetail.get("variantGroupId")))).isEqualTo(updatedVariantGroupId);
        assertThat(updatedDetail.get("productFamilyName")).isEqualTo("Premium Primer");
        assertThat(decimalValue(updatedDetail.get("basePrice"))).isEqualByComparingTo("1325.00");
        assertThat(decimalValue(updatedDetail.get("minDiscountPercent"))).isEqualByComparingTo("7.50");
        assertThat(decimalValue(updatedDetail.get("minSellingPrice"))).isEqualByComparingTo("1225.00");
        Map<String, Object> updatedDetailMetadata = metadata(updatedDetail);
        assertThat(updatedDetailMetadata)
                .containsEntry("productType", "decorative")
                .doesNotContainKeys("wipAccountId", "wastageAccountId");

        ResponseEntity<Map> updatedListResponse = rest.exchange(
                "/api/v1/catalog/products?brandId=" + activeBrand.getId(),
                HttpMethod.GET,
                new HttpEntity<>(salesHeaders),
                Map.class);
        assertThat(updatedListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updatedListItem = pageContent(updatedListResponse).stream()
                .filter(item -> String.valueOf(member.get("id")).equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
        assertThat(metadata(updatedListItem))
                .containsEntry("productType", "decorative")
                .doesNotContainKeys("wipAccountId", "wastageAccountId");
    }

    @Test
    void searchAndGetProducts_includeAccountingMetadata_forAdminAndAccounting_only() {
        ProductionBrand activeBrand = saveBrand("Canonical Metadata " + shortId(), true);
        Account wipAccount = ensureAccount("WIP-R-" + shortId(), "Read WIP", AccountType.ASSET);

        ResponseEntity<Map> createResponse = postCatalogProducts(basePayload(activeBrand.getId()), false);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> member = members(data(createResponse)).getFirst();
        Long productId = ((Number) member.get("id")).longValue();

        ResponseEntity<Map> detailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> detailData = data(detailResponse);

        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("brandId", activeBrand.getId());
        updatePayload.put("name", detailData.get("name"));
        updatePayload.put("colors", detailData.get("colors"));
        updatePayload.put("sizes", detailData.get("sizes"));
        updatePayload.put("cartonSizes", detailData.get("cartonSizes"));
        updatePayload.put("unitOfMeasure", detailData.get("unitOfMeasure"));
        updatePayload.put("hsnCode", detailData.get("hsnCode"));
        updatePayload.put("basePrice", detailData.get("basePrice"));
        updatePayload.put("gstRate", detailData.get("gstRate"));
        updatePayload.put("minDiscountPercent", detailData.get("minDiscountPercent"));
        updatePayload.put("minSellingPrice", detailData.get("minSellingPrice"));
        updatePayload.put("metadata", Map.of(
                "productType", "decorative",
                "wipAccountId", wipAccount.getId(),
                "wastageAccountId", company.getDefaultCogsAccountId()));
        updatePayload.put("active", true);

        ResponseEntity<Map> updateResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> adminDetailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class);
        assertThat(adminDetailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metadata(data(adminDetailResponse)))
                .containsEntry("productType", "decorative")
                .containsKeys("wipAccountId", "wastageAccountId");

        ResponseEntity<Map> accountingDetailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(accountingDetailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metadata(data(accountingDetailResponse)))
                .containsEntry("productType", "decorative")
                .containsKeys("wipAccountId", "wastageAccountId");

        ResponseEntity<Map> salesDetailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(salesDetailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metadata(data(salesDetailResponse)))
                .containsEntry("productType", "decorative")
                .doesNotContainKeys("wipAccountId", "wastageAccountId");

        ResponseEntity<Map> adminListResponse = rest.exchange(
                "/api/v1/catalog/products?brandId=" + activeBrand.getId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class);
        assertThat(adminListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> adminListItem = pageContent(adminListResponse).stream()
                .filter(item -> String.valueOf(productId).equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
        assertThat(metadata(adminListItem)).containsKeys("wipAccountId", "wastageAccountId");

        ResponseEntity<Map> salesListResponse = rest.exchange(
                "/api/v1/catalog/products?brandId=" + activeBrand.getId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(salesListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> salesListItem = pageContent(salesListResponse).stream()
                .filter(item -> String.valueOf(productId).equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
        assertThat(metadata(salesListItem)).doesNotContainKeys("wipAccountId", "wastageAccountId");
    }

    @Test
    void updateProduct_rejectsInvalidFinishedGoodAccountMetadata() {
        ProductionBrand activeBrand = saveBrand("Canonical Invalid Account " + shortId(), true);
        ResponseEntity<Map> createResponse = postCatalogProducts(basePayload(activeBrand.getId()), false);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long productId = Long.valueOf(String.valueOf(members(data(createResponse)).getFirst().get("id")));
        ResponseEntity<Map> detailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> detailData = data(detailResponse);
        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("brandId", activeBrand.getId());
        updatePayload.put("name", detailData.get("name"));
        updatePayload.put("colors", detailData.get("colors"));
        updatePayload.put("sizes", detailData.get("sizes"));
        updatePayload.put("cartonSizes", detailData.get("cartonSizes"));
        updatePayload.put("unitOfMeasure", detailData.get("unitOfMeasure"));
        updatePayload.put("hsnCode", detailData.get("hsnCode"));
        updatePayload.put("basePrice", detailData.get("basePrice"));
        updatePayload.put("gstRate", detailData.get("gstRate"));
        updatePayload.put("minDiscountPercent", detailData.get("minDiscountPercent"));
        updatePayload.put("minSellingPrice", detailData.get("minSellingPrice"));
        updatePayload.put("metadata", Map.of("fgValuationAccountId", 999999L));
        updatePayload.put("active", true);

        ResponseEntity<Map> updateResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(errorData(updateResponse).get("reason")))
                .contains("invalid account id 999999")
                .contains("fgValuationAccountId");
    }

    @Test
    void createProduct_reusesVariantGroupAcrossSeparateFamilySlices() {
        ProductionBrand activeBrand = saveBrand("Canonical Family " + shortId(), true);
        String familyName = "Family Primer " + shortId();

        ResponseEntity<Map> firstResponse = postCatalogProducts(
                familyPayload(activeBrand.getId(), familyName, "FINISHED_GOOD", List.of("WHITE"), List.of("1L"), Map.of("productType", "decorative")),
                false);
        ResponseEntity<Map> secondResponse = postCatalogProducts(
                familyPayload(activeBrand.getId(), familyName, "FINISHED_GOOD", List.of("BLUE"), List.of("1L"), Map.of("productType", "decorative")),
                false);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> firstData = data(firstResponse);
        Map<String, Object> secondData = data(secondResponse);
        UUID firstVariantGroupId = UUID.fromString(String.valueOf(firstData.get("variantGroupId")));
        UUID secondVariantGroupId = UUID.fromString(String.valueOf(secondData.get("variantGroupId")));

        assertThat(secondVariantGroupId).isEqualTo(firstVariantGroupId);
        assertThat(productRepository.countByCompanyAndVariantGroupId(company, firstVariantGroupId)).isEqualTo(2);
        assertThat(members(firstData)).extracting(member -> String.valueOf(member.get("sku")))
                .containsExactly(buildCanonicalSku("FINISHED_GOOD", familyName, "WHITE", "1L"));
        assertThat(members(secondData)).extracting(member -> String.valueOf(member.get("sku")))
                .containsExactly(buildCanonicalSku("FINISHED_GOOD", familyName, "BLUE", "1L"));
    }

    @Test
    void createProduct_reusesVariantGroupAcrossCaseAndPunctuationVariants() {
        ProductionBrand activeBrand = saveBrand("Canonical Canon " + shortId(), true);

        ResponseEntity<Map> firstResponse = postCatalogProducts(
                familyPayload(activeBrand.getId(), "Primer", "FINISHED_GOOD", List.of("WHITE"), List.of("1L"), Map.of("productType", "decorative")),
                false);
        ResponseEntity<Map> secondResponse = postCatalogProducts(
                familyPayload(activeBrand.getId(), " primer!! ", "finished good", List.of("BLUE"), List.of("1-l"), Map.of("productType", "decorative")),
                false);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID firstVariantGroupId = UUID.fromString(String.valueOf(data(firstResponse).get("variantGroupId")));
        UUID secondVariantGroupId = UUID.fromString(String.valueOf(data(secondResponse).get("variantGroupId")));

        assertThat(secondVariantGroupId).isEqualTo(firstVariantGroupId);
    }

    @Test
    void updateProduct_preservesRawMaterialCategory_andResyncsRawMaterialTruth() {
        ProductionBrand activeBrand = saveBrand("Canonical Raw " + shortId(), true);

        ResponseEntity<Map> createResponse = postCatalogProducts(rawMaterialPayload(activeBrand.getId()), false);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> createData = data(createResponse);
        Map<String, Object> member = members(createData).getFirst();
        Long productId = Long.valueOf(String.valueOf(member.get("id")));
        String sku = String.valueOf(member.get("sku"));

        ResponseEntity<Map> detailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> detailData = data(detailResponse);
        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("brandId", activeBrand.getId());
        updatePayload.put("name", "Titanium Dioxide Updated");
        updatePayload.put("colors", detailData.get("colors"));
        updatePayload.put("sizes", detailData.get("sizes"));
        updatePayload.put("cartonSizes", detailData.get("cartonSizes"));
        updatePayload.put("unitOfMeasure", detailData.get("unitOfMeasure"));
        updatePayload.put("hsnCode", detailData.get("hsnCode"));
        updatePayload.put("gstRate", detailData.get("gstRate"));
        updatePayload.put("active", true);

        ResponseEntity<Map> updateResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(updateResponse)).containsEntry("category", "RAW_MATERIAL");
        assertThat(data(updateResponse)).containsEntry("name", "Titanium Dioxide Updated");

        ProductionProduct updatedProduct = productRepository.findByCompanyAndId(company, productId).orElseThrow();
        assertThat(updatedProduct.getCategory()).isEqualTo("RAW_MATERIAL");

        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isEmpty();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent()
                .get()
                .satisfies(material -> {
                    assertThat(material.getName()).isEqualTo("Titanium Dioxide Updated");
                    assertThat(material.getUnitType()).isEqualTo("KG");
                    assertThat(material.getInventoryAccountId()).isEqualTo(company.getDefaultInventoryAccountId());
                    assertThat(material.getGstRate()).isEqualByComparingTo("18.00");
                });
    }

    @Test
    void previewAndCommit_matrixCreate_shareCandidatePlan_and_previewDoesNotPersist() {
        ProductionBrand activeBrand = saveBrand("Canonical Matrix " + shortId(), true);
        Map<String, Object> payload = matrixPayload(activeBrand.getId(), "Premium Emulsion " + shortId());

        ResponseEntity<Map> previewResponse = postCatalogProducts(payload, true);
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> previewData = data(previewResponse);
        UUID previewVariantGroupId = UUID.fromString(String.valueOf(previewData.get("variantGroupId")));
        List<Map<String, Object>> previewMembers = members(previewData);
        assertThat(previewData).containsEntry("preview", true);
        assertThat(previewData).containsEntry("candidateCount", 16);
        assertThat(previewMembers).hasSize(16);
        assertThat(conflicts(previewData)).isEmpty();
        assertThat(downstreamEffects(previewData)).containsEntry("finishedGoodMembers", 16);
        assertThat(productRepository.countByCompanyAndVariantGroupId(company, previewVariantGroupId)).isZero();
        for (Map<String, Object> member : previewMembers) {
            String sku = String.valueOf(member.get("sku"));
            assertThat(member.get("id")).isNull();
            assertThat(member.get("publicId")).isNull();
            assertThat(productRepository.findByCompanyAndSkuCode(company, sku)).isEmpty();
            assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isEmpty();
        }

        ResponseEntity<Map> commitResponse = postCatalogProducts(payload, false);
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> commitData = data(commitResponse);
        UUID commitVariantGroupId = UUID.fromString(String.valueOf(commitData.get("variantGroupId")));
        List<Map<String, Object>> commitMembers = members(commitData);
        assertThat(commitData).containsEntry("preview", false);
        assertThat(commitData).containsEntry("candidateCount", 16);
        assertThat(commitMembers).hasSize(16);
        assertThat(commitVariantGroupId).isEqualTo(previewVariantGroupId);
        assertThat(commitMembers.stream().map(member -> String.valueOf(member.get("sku"))).collect(Collectors.toSet()))
                .isEqualTo(previewMembers.stream().map(member -> String.valueOf(member.get("sku"))).collect(Collectors.toSet()));
        assertThat(commitMembers.stream().map(member -> String.valueOf(member.get("id"))).collect(Collectors.toSet())).hasSize(16);
        assertThat(commitMembers.stream().map(member -> String.valueOf(member.get("publicId"))).collect(Collectors.toSet())).hasSize(16);
        assertThat(productRepository.countByCompanyAndVariantGroupId(company, commitVariantGroupId)).isEqualTo(16);
        for (Map<String, Object> member : commitMembers) {
            String sku = String.valueOf(member.get("sku"));
            assertThat(productRepository.findByCompanyAndSkuCode(company, sku)).isPresent();
            assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isPresent();
        }
    }

    @Test
    void commitConflict_failsClosedWithoutPartialPersistence_and_bulkRouteIsRetired() {
        ProductionBrand activeBrand = saveBrand("Canonical Conflict " + shortId(), true);
        Map<String, Object> payload = matrixPayload(activeBrand.getId(), "Conflict Family " + shortId());

        ResponseEntity<Map> previewResponse = postCatalogProducts(payload, true);
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> previewData = data(previewResponse);
        UUID previewVariantGroupId = UUID.fromString(String.valueOf(previewData.get("variantGroupId")));
        List<Map<String, Object>> previewMembers = members(previewData);
        Map<String, Object> conflictingMember = previewMembers.getFirst();
        String conflictingSku = String.valueOf(conflictingMember.get("sku"));

        seedExistingProduct(activeBrand, conflictingSku, String.valueOf(conflictingMember.get("productName")),
                String.valueOf(conflictingMember.get("color")), String.valueOf(conflictingMember.get("size")));

        ResponseEntity<Map> conflictResponse = postCatalogProducts(payload, false);
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> errorData = errorData(conflictResponse);
        assertThat(errorData).containsEntry("code", "CONC_001");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) errorData.get("details");
        assertThat(details).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflictDetails = (List<Map<String, Object>>) details.get("conflicts");
        assertThat(conflictDetails)
                .extracting(detail -> String.valueOf(detail.get("sku")))
                .contains(conflictingSku);
        assertThat(productRepository.countByCompanyAndVariantGroupId(company, previewVariantGroupId)).isZero();
        for (Map<String, Object> member : previewMembers) {
            String sku = String.valueOf(member.get("sku"));
            if (conflictingSku.equals(sku)) {
                assertThat(productRepository.findByCompanyAndSkuCode(company, sku)).isPresent();
                continue;
            }
            assertThat(productRepository.findByCompanyAndSkuCode(company, sku)).isEmpty();
            assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isEmpty();
            assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isEmpty();
        }

        ResponseEntity<Map> bulkRouteResponse = rest.exchange(
                "/api/v1/catalog/products/bulk",
                HttpMethod.POST,
                new HttpEntity<>(List.of(Map.of("ignored", "payload")), headers),
                Map.class);
        assertThat(bulkRouteResponse.getStatusCode().is4xxClientError()).isTrue();
        assertThat(bulkRouteResponse.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createProduct_allowsAdminAndAccounting_only() {
        ProductionBrand activeBrand = saveBrand("Canonical Roles " + shortId(), true);
        Map<String, Object> payload = basePayload(activeBrand.getId());

        ResponseEntity<Map> adminResponse = postCatalogProducts(payload, true, authHeaders());
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = postCatalogProducts(
                payload,
                true,
                authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE));
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> salesResponse = postCatalogProducts(
                payload,
                true,
                authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE));
        assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> factoryResponse = postCatalogProducts(
                payload,
                true,
                authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE));
        assertThat(factoryResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateProduct_allowsAdminAndAccounting_only() {
        ProductionBrand activeBrand = saveBrand("Canonical Update Roles " + shortId(), true);
        ResponseEntity<Map> createResponse = postCatalogProducts(basePayload(activeBrand.getId()), false);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long productId = Long.valueOf(String.valueOf(members(data(createResponse)).getFirst().get("id")));
        ResponseEntity<Map> detailResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> detailData = data(detailResponse);
        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("brandId", activeBrand.getId());
        updatePayload.put("name", "Restricted Update");
        updatePayload.put("colors", detailData.get("colors"));
        updatePayload.put("sizes", detailData.get("sizes"));
        updatePayload.put("cartonSizes", detailData.get("cartonSizes"));
        updatePayload.put("unitOfMeasure", detailData.get("unitOfMeasure"));
        updatePayload.put("hsnCode", detailData.get("hsnCode"));
        updatePayload.put("basePrice", detailData.get("basePrice"));
        updatePayload.put("gstRate", detailData.get("gstRate"));
        updatePayload.put("minDiscountPercent", detailData.get("minDiscountPercent"));
        updatePayload.put("minSellingPrice", detailData.get("minSellingPrice"));
        updatePayload.put("metadata", detailData.get("metadata"));
        updatePayload.put("active", true);

        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, authHeaders()),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> salesResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> factoryResponse = rest.exchange(
                "/api/v1/catalog/products/" + productId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, authHeaders(FACTORY_EMAIL, PASSWORD, COMPANY_CODE)),
                Map.class);
        assertThat(factoryResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void configureDefaultAccounts() {
        Account inventory = ensureAccount("INV-" + shortId(), "Inventory", AccountType.ASSET);
        Account cogs = ensureAccount("COGS-" + shortId(), "COGS", AccountType.COGS);
        Account revenue = ensureAccount("REV-" + shortId(), "Revenue", AccountType.REVENUE);
        Account tax = ensureAccount("GST-" + shortId(), "GST", AccountType.LIABILITY);
        company.setDefaultInventoryAccountId(inventory.getId());
        company.setDefaultCogsAccountId(cogs.getId());
        company.setDefaultRevenueAccountId(revenue.getId());
        company.setDefaultTaxAccountId(tax.getId());
        companyRepository.save(company);
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
        brand.setCode(buildBrandCode(name));
        brand.setActive(active);
        return brandRepository.save(brand);
    }

    private void seedExistingProduct(ProductionBrand brand,
                                     String sku,
                                     String productName,
                                     String color,
                                     String size) {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName(productName);
        product.setCategory("FINISHED_GOOD");
        product.setDefaultColour(color);
        product.setSizeLabel(size);
        product.setUnitOfMeasure("LITER");
        product.setHsnCode("320910");
        product.setSkuCode(sku);
        product.setActive(true);
        product.setBasePrice(new BigDecimal("999.00"));
        product.setGstRate(new BigDecimal("18.00"));
        productRepository.saveAndFlush(product);
    }

    private HttpHeaders authHeaders() {
        return authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE);
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
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

    private ResponseEntity<Map> postCatalogProducts(Map<String, Object> payload, boolean preview) {
        return postCatalogProducts(payload, preview, headers);
    }

    private ResponseEntity<Map> postCatalogProducts(Map<String, Object> payload, boolean preview, HttpHeaders requestHeaders) {
        String path = preview ? "/api/v1/catalog/products?preview=true" : "/api/v1/catalog/products";
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, requestHeaders), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageContent(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) data(response).get("content");
    }

    private Map<String, Object> basePayload(Long brandId) {
        return basePayload(brandId, "Premium Primer " + shortId());
    }

    private Map<String, Object> basePayload(Long brandId, String baseProductName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (brandId != null) {
            payload.put("brandId", brandId);
        }
        payload.put("baseProductName", baseProductName);
        payload.put("category", "FINISHED_GOOD");
        payload.put("itemClass", "FINISHED_GOOD");
        payload.put("unitOfMeasure", "LITER");
        payload.put("hsnCode", "320910");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("1200.00"));
        payload.put("minDiscountPercent", new BigDecimal("5.00"));
        payload.put("minSellingPrice", new BigDecimal("1140.00"));
        payload.put("colors", List.of("WHITE"));
        payload.put("sizes", List.of("1L"));
        payload.put("metadata", Map.of("productType", "decorative"));
        return payload;
    }

    private Map<String, Object> matrixPayload(Long brandId, String baseProductName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brandId", brandId);
        payload.put("baseProductName", baseProductName);
        payload.put("category", "FINISHED_GOOD");
        payload.put("itemClass", "FINISHED_GOOD");
        payload.put("unitOfMeasure", "LITER");
        payload.put("hsnCode", "320910");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("1600.00"));
        payload.put("minDiscountPercent", new BigDecimal("7.50"));
        payload.put("minSellingPrice", new BigDecimal("1480.00"));
        payload.put("colors", List.of("WHITE", "BLUE", "GREEN", "BLACK"));
        payload.put("sizes", List.of("1L", "4L", "10L", "20L"));
        payload.put("metadata", Map.of("productType", "decorative"));
        return payload;
    }

    private Map<String, Object> familyPayload(Long brandId,
                                              String baseProductName,
                                              String category,
                                              List<String> colors,
                                              List<String> sizes,
                                              Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brandId", brandId);
        payload.put("baseProductName", baseProductName);
        payload.put("category", category);
        payload.put("itemClass", itemClassForCategory(category));
        payload.put("unitOfMeasure", "LITER");
        payload.put("hsnCode", "320910");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("1200.00"));
        payload.put("minDiscountPercent", new BigDecimal("5.00"));
        payload.put("minSellingPrice", new BigDecimal("1140.00"));
        payload.put("colors", colors);
        payload.put("sizes", sizes);
        payload.put("metadata", metadata);
        return payload;
    }

    private Map<String, Object> rawMaterialPayload(Long brandId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brandId", brandId);
        payload.put("baseProductName", "Titanium Dioxide");
        payload.put("category", "RAW_MATERIAL");
        payload.put("itemClass", "RAW_MATERIAL");
        payload.put("unitOfMeasure", "KG");
        payload.put("hsnCode", "320611");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("500.00"));
        payload.put("minDiscountPercent", BigDecimal.ZERO);
        payload.put("minSellingPrice", new BigDecimal("500.00"));
        payload.put("colors", List.of("NATURAL"));
        payload.put("sizes", List.of("25KG"));
        payload.put("metadata", Map.of("inventoryAccountId", company.getDefaultInventoryAccountId()));
        return payload;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> errors(ResponseEntity<Map> response) {
        return (Map<String, Object>) errorData(response).get("errors");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("members");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conflicts(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("conflicts");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> data) {
        return (Map<String, Object>) data.get("metadata");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> downstreamEffects(Map<String, Object> data) {
        return (Map<String, Object>) data.get("downstreamEffects");
    }

    private void assertCatalogReadFields(Map<String, Object> product,
                                         UUID variantGroupId,
                                         String productFamilyName,
                                         String color,
                                         String size) {
        assertThat(product.get("variantGroupId")).isEqualTo(variantGroupId.toString());
        assertThat(product.get("productFamilyName")).isEqualTo(productFamilyName);
        assertThat(product.get("category")).isEqualTo("FINISHED_GOOD");
        assertThat(product.get("unitOfMeasure")).isEqualTo("LITER");
        assertThat(product.get("hsnCode")).isEqualTo("320910");
        assertThat(decimalValue(product.get("basePrice"))).isEqualByComparingTo("1200.00");
        assertThat(decimalValue(product.get("gstRate"))).isEqualByComparingTo("18.00");
        assertThat(decimalValue(product.get("minDiscountPercent"))).isEqualByComparingTo("5.00");
        assertThat(decimalValue(product.get("minSellingPrice"))).isEqualByComparingTo("1140.00");
        assertThat(listOfStrings(product.get("colors"))).containsExactly(color);
        assertThat(listOfStrings(product.get("sizes"))).containsExactly(size);
        assertThat(cartonMappings(product.get("cartonSizes"))).containsExactly(size + ":1");
        assertThat(metadata(product)).containsEntry("productType", "decorative");
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfStrings(Object value) {
        return value == null ? List.of() : ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> cartonMappings(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Map<String, Object>>) value).stream()
                .map(mapping -> String.valueOf(mapping.get("size")) + ":" + String.valueOf(mapping.get("piecesPerCarton")))
                .toList();
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(number));
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String buildCanonicalSku(String itemClass, String baseProductName, String color, String size) {
        return List.of(itemClassSkuPrefix(itemClass), baseProductName, color, size).stream()
                .map(this::sanitizeSkuFragment)
                .collect(Collectors.joining("-"))
                .replaceAll("-{2,}", "-");
    }

    private String itemClassForCategory(String category) {
        String normalized = category == null ? "" : category.trim().replace(' ', '_').toUpperCase();
        return switch (normalized) {
            case "RAW_MATERIAL" -> "RAW_MATERIAL";
            case "PACKAGING", "PACKAGING_RAW_MATERIAL" -> "PACKAGING_RAW_MATERIAL";
            default -> "FINISHED_GOOD";
        };
    }

    private String itemClassSkuPrefix(String itemClass) {
        return switch (itemClassForCategory(itemClass)) {
            case "RAW_MATERIAL" -> "RM";
            case "PACKAGING_RAW_MATERIAL" -> "PKG";
            default -> "FG";
        };
    }

    private String sanitizeSkuFragment(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9-]", "");
    }

    private String buildBrandCode(String name) {
        String sanitized = sanitizeSkuFragment(name).replace("-", "");
        return sanitized.length() > 12 ? sanitized.substring(0, 12) : sanitized;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
