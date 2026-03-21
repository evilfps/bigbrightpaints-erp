package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("critical")
class ProductionCatalogServiceCanonicalEntryTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private ProductionBrandRepository brandRepository;
    @Mock private ProductionProductRepository productRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock private CatalogImportRepository catalogImportRepository;
    @Mock private AuditService auditService;
    @Mock private PlatformTransactionManager transactionManager;

    private ProductionCatalogService service;
    private Company company;
    private ProductionBrand brand;

    @BeforeEach
    void setUp() {
        service = new ProductionCatalogService(
                companyContextService,
                brandRepository,
                productRepository,
                finishedGoodRepository,
                rawMaterialRepository,
                companyEntityLookup,
                companyDefaultAccountsService,
                catalogImportRepository,
                auditService,
                transactionManager);

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 10L);
        company.setCode("BBP");

        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setCompany(company);
        brand.setName("BigBright");
        brand.setCode("BBR");
        brand.setActive(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(companyEntityLookup.requireProductionBrand(company, 11L)).thenReturn(brand);
        when(productRepository.findByCompanyAndSkuCodeIn(eq(company), anySet())).thenReturn(List.of());
        when(productRepository.findByBrandAndProductNameIgnoreCase(eq(brand), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsNullRequest() {
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(null, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Canonical product request is required");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsMissingBrandId() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setBrandId(null);

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("brandId is required");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsEmptyPackedAndSkuUnsafeCanonicalTokens() {
        CatalogProductEntryRequest emptyColors = request("RAW_MATERIAL", List.of(), List.of("1L"));
        CatalogProductEntryRequest packedColors = request("RAW_MATERIAL", List.of("WHITE/BLUE"), List.of("1L"));
        CatalogProductEntryRequest blankBaseName = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        blankBaseName.setBaseProductName("   ");
        CatalogProductEntryRequest invalidSize = request("RAW_MATERIAL", List.of("WHITE"), List.of("***"));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(emptyColors, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("colors must contain at least one value");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(packedColors, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("packed multi-value tokens");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(blankBaseName, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("baseProductName is required");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(invalidSize, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Canonical product sizes must contain at least one alphanumeric SKU character");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsCanonicalSkusLongerThanDatabaseLimit() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setBaseProductName("P".repeat(116));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Canonical product SKU exceeds 128 characters");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsCanonicalFamilyNamesLongerThanDatabaseLimit() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setBaseProductName("P".repeat(256));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("productFamilyName exceeds 255 characters");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsCanonicalProductNamesLongerThanDatabaseLimit() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("RED"), List.of("1L"));
        request.setBaseProductName("Primer" + ".".repeat(245));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("productName exceeds 255 characters");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsOutOfRangePricingInputs() {
        CatalogProductEntryRequest negativeBasePrice = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        negativeBasePrice.setBasePrice(new BigDecimal("-1.00"));
        CatalogProductEntryRequest negativeDiscount = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        negativeDiscount.setMinDiscountPercent(new BigDecimal("-1.00"));
        CatalogProductEntryRequest excessiveDiscount = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        excessiveDiscount.setMinDiscountPercent(new BigDecimal("101.00"));
        CatalogProductEntryRequest negativeMinSellingPrice = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        negativeMinSellingPrice.setMinSellingPrice(new BigDecimal("-1.00"));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(negativeBasePrice, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("basePrice cannot be negative");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(negativeDiscount, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("minDiscountPercent cannot be negative");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(excessiveDiscount, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("minDiscountPercent cannot be greater than 100");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(negativeMinSellingPrice, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("minSellingPrice cannot be negative");
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsOversizedUnitOfMeasureAndHsnCode() {
        CatalogProductEntryRequest oversizedUnit = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        oversizedUnit.setUnitOfMeasure("L".repeat(65));
        CatalogProductEntryRequest oversizedHsn = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        oversizedHsn.setHsnCode("9".repeat(33));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(oversizedUnit, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("unitOfMeasure exceeds 64 characters");
        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(oversizedHsn, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("hsnCode exceeds 32 characters");
    }

    @Test
    void createOrPreviewCatalogProducts_previewFlagsDuplicateRequestConflicts_forRawMaterials() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE", "white"), List.of("1L"));

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(request, true);

        assertThat(response.preview()).isTrue();
        assertThat(response.candidateCount()).isEqualTo(2);
        assertThat(response.downstreamEffects().finishedGoodMembers()).isZero();
        assertThat(response.downstreamEffects().rawMaterialMembers()).isEqualTo(2);
        assertThat(response.conflicts())
                .extracting(CatalogProductEntryResponse.Conflict::reason)
                .containsOnly("DUPLICATE_IN_REQUEST");
    }

    @Test
    void createOrPreviewCatalogProducts_previewFlagsExistingSkuConflicts() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE", "BLUE"), List.of("1L"));
        String existingSku = canonicalSku("Primer", "BLUE", "1L");
        when(productRepository.findByCompanyAndSkuCodeIn(eq(company), anySet()))
                .thenReturn(List.of(existingProduct(existingSku)));

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(request, true);

        assertThat(response.conflicts())
                .extracting(CatalogProductEntryResponse.Conflict::sku, CatalogProductEntryResponse.Conflict::reason)
                .containsExactly(new org.assertj.core.groups.Tuple(existingSku, "SKU_ALREADY_EXISTS"));
    }

    @Test
    void createOrPreviewCatalogProducts_previewFlagsExistingProductNameConflicts() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE", "BLUE"), List.of("1L"));
        String conflictingSku = canonicalSku("Primer", "BLUE", "1L");
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer BLUE 1L"))
                .thenReturn(Optional.of(existingProduct("LEGACY-PRIMER-BLUE", "Primer BLUE 1L")));

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(request, true);

        assertThat(response.conflicts())
                .extracting(CatalogProductEntryResponse.Conflict::sku,
                        CatalogProductEntryResponse.Conflict::reason,
                        CatalogProductEntryResponse.Conflict::productName)
                .containsExactly(new org.assertj.core.groups.Tuple(
                        conflictingSku,
                        "PRODUCT_NAME_ALREADY_EXISTS",
                        "Primer BLUE 1L"));
    }

    @Test
    void createOrPreviewCatalogProducts_rejectsNullColors() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setColors(null);

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("colors must contain at least one value");
    }

    @Test
    void createOrPreviewCatalogProducts_previewRejectsInvalidRawMaterialInventoryAccount() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setMetadata(Map.of("inventoryAccountId", 999L));
        when(companyEntityLookup.requireAccount(company, 999L)).thenThrow(new IllegalArgumentException("missing"));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("invalid inventory account id 999");
    }

    @Test
    void createOrPreviewCatalogProducts_previewAcceptsRawMaterialInventoryAlias() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        request.setMetadata(Map.of("rawMaterialInventoryAccountId", 555L));
        when(companyEntityLookup.requireAccount(company, 555L)).thenReturn(account(555L));

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(request, true);

        assertThat(response.metadata()).containsEntry("rawMaterialInventoryAccountId", 555L);
    }

    @Test
    void createOrPreviewCatalogProducts_previewEnrichesFinishedGoodsWithPostingDefaults() {
        when(companyDefaultAccountsService.requireDefaults()).thenReturn(
                new CompanyDefaultAccountsService.DefaultAccounts(101L, 102L, 103L, 104L, 105L));
        when(companyEntityLookup.requireAccount(company, 101L)).thenReturn(account(101L));
        when(companyEntityLookup.requireAccount(company, 102L)).thenReturn(account(102L));
        when(companyEntityLookup.requireAccount(company, 103L)).thenReturn(account(103L));
        when(companyEntityLookup.requireAccount(company, 104L)).thenReturn(account(104L));
        when(companyEntityLookup.requireAccount(company, 105L)).thenReturn(account(105L));

        CatalogProductEntryRequest request = request("FINISHED_GOOD", List.of("WHITE"), List.of("1L"));

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(request, true);

        assertThat(response.preview()).isTrue();
        assertThat(response.metadata())
                .containsEntry("fgValuationAccountId", 101L)
                .containsEntry("fgCogsAccountId", 102L)
                .containsEntry("fgRevenueAccountId", 103L)
                .containsEntry("fgDiscountAccountId", 104L)
                .containsEntry("fgTaxAccountId", 105L);
    }

    @Test
    void createOrPreviewCatalogProducts_rethrowsUnexpectedCreateFailures() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE"), List.of("1L"));
        when(productRepository.save(any(ProductionProduct.class))).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void createOrPreviewCatalogProducts_translatesWriteTimeDuplicateIntoConcurrencyConflict() {
        CatalogProductEntryRequest request = request("RAW_MATERIAL", List.of("WHITE", "BLUE"), List.of("1L"));
        String conflictingSku = canonicalSku("Primer", "WHITE", "1L");
        when(productRepository.findByCompanyAndSkuCode(company, conflictingSku))
                .thenReturn(Optional.empty(), Optional.of(existingProduct(conflictingSku)));
        when(productRepository.save(any(ProductionProduct.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.createOrPreviewCatalogProducts(request, false))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getMessage()).contains(conflictingSku);
                    assertThat(ex.getDetails()).containsKeys("conflicts", "wouldCreate");
                    @SuppressWarnings("unchecked")
                    List<CatalogProductEntryResponse.Member> wouldCreate =
                            (List<CatalogProductEntryResponse.Member>) ex.getDetails().get("wouldCreate");
                    assertThat(wouldCreate)
                            .extracting(CatalogProductEntryResponse.Member::sku)
                            .containsExactly(canonicalSku("Primer", "BLUE", "1L"));
                });
    }

    @Test
    void canonicalHelperMethods_keepFamilyGroupingStableAcrossSubsetAndOrderChanges() {
        UUID fullMatrixVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                "Primer",
                "RAW_MATERIAL",
                "LITER",
                "320910",
                List.of("WHITE", "BLUE"),
                List.of("1L", "4L"));
        UUID subsetVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                "Primer",
                "RAW_MATERIAL",
                "LITER",
                "320910",
                List.of("BLUE"),
                List.of("4L"));
        UUID reorderedVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                "Primer",
                "RAW_MATERIAL",
                "LITER",
                "320910",
                List.of("BLUE", "WHITE"),
                List.of("4L", "1L"));
        UUID differentFamilyVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                "Sealer",
                "RAW_MATERIAL",
                "LITER",
                "320910",
                List.of("WHITE"),
                List.of("1L"));
        UUID caseAndPunctuationVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                " primer!! ",
                "raw material",
                "li-ter",
                "3209-10",
                List.of("WHITE"),
                List.of("1L"));
        @SuppressWarnings("unchecked")
        Set<String> single = (Set<String>) ReflectionTestUtils.invokeMethod(service, "singleVariantSet", "WHITE");
        @SuppressWarnings("unchecked")
        Set<String> empty = (Set<String>) ReflectionTestUtils.invokeMethod(service, "singleVariantSet", "   ");

        assertThat(fullMatrixVariantGroupId).isEqualTo(subsetVariantGroupId);
        assertThat(fullMatrixVariantGroupId).isEqualTo(reorderedVariantGroupId);
        assertThat(fullMatrixVariantGroupId).isEqualTo(caseAndPunctuationVariantGroupId);
        assertThat(differentFamilyVariantGroupId).isNotEqualTo(fullMatrixVariantGroupId);
        assertThat(single).containsExactly("WHITE");
        assertThat(empty).isEmpty();
    }

    @Test
    void canonicalHelperMethods_coverBlankCartonsUnlimitedSkuAndExplicitConflictOverrides() {
        @SuppressWarnings("unchecked")
        Map<String, Integer> emptyCartons = (Map<String, Integer>) ReflectionTestUtils.invokeMethod(
                service,
                "defaultCartonSizes",
                "   ");
        String unboundedSkuFragment = ReflectionTestUtils.invokeMethod(
                service,
                "requireCanonicalSkuFragment",
                "baseProductName",
                "Primer",
                0);
        Object plan = ReflectionTestUtils.invokeMethod(
                service,
                "prepareCatalogProductEntryPlan",
                company,
                request("RAW_MATERIAL", List.of("WHITE"), List.of("1L")));
        CatalogProductEntryResponse.Conflict overrideConflict = new CatalogProductEntryResponse.Conflict(
                "BBR-PRIMER-WHITE-1L",
                "MANUAL_OVERRIDE",
                "Primer WHITE 1L",
                "WHITE",
                "1L");
        CatalogProductEntryResponse overriddenResponse = ReflectionTestUtils.invokeMethod(
                service,
                "toCatalogProductEntryResponse",
                plan,
                List.of(overrideConflict),
                true);

        assertThat(emptyCartons).isEmpty();
        assertThat(unboundedSkuFragment).isEqualTo("PRIMER");
        assertThat(overriddenResponse.conflicts()).containsExactly(overrideConflict);
    }

    @Test
    void canonicalHelperMethods_fallbackToPlanConflictsForNullOrEmptyOverrides() {
        Object duplicatePlan = ReflectionTestUtils.invokeMethod(
                service,
                "prepareCatalogProductEntryPlan",
                company,
                request("RAW_MATERIAL", List.of("WHITE", "white"), List.of("1L")));

        CatalogProductEntryResponse nullOverrideResponse = ReflectionTestUtils.invokeMethod(
                service,
                "toCatalogProductEntryResponse",
                duplicatePlan,
                (Object) null,
                true);
        CatalogProductEntryResponse emptyOverrideResponse = ReflectionTestUtils.invokeMethod(
                service,
                "toCatalogProductEntryResponse",
                duplicatePlan,
                List.of(),
                true);

        assertThat(nullOverrideResponse.conflicts())
                .extracting(CatalogProductEntryResponse.Conflict::reason)
                .containsOnly("DUPLICATE_IN_REQUEST");
        assertThat(emptyOverrideResponse.conflicts())
                .extracting(CatalogProductEntryResponse.Conflict::reason)
                .containsOnly("DUPLICATE_IN_REQUEST");
    }

    @Test
    void createVariants_dryRun_generatesCanonicalSkuFromSizeFragments() {
        BulkVariantResponse response = service.createVariants(
                bulkVariantRequest(List.of("WHITE"), List.of("1L")),
                true);

        assertThat(response.generated())
                .extracting(BulkVariantResponse.VariantItem::sku)
                .containsExactly("BBR-PRIMER-WHITE-1L");
        assertThat(response.wouldCreate())
                .extracting(BulkVariantResponse.VariantItem::size)
                .containsExactly("1L");
    }

    @Test
    void canonicalHelperMethods_validateMimeParameterSections() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isValidMimeParameterSection",
                "charset=utf-8;header=present")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isValidMimeParameterSection",
                "charset=utf-8;;header=present")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isValidMimeParameterSection",
                "=utf-8")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isValidMimeParameterSection",
                "charset=")).isFalse();
    }

    private CatalogProductEntryRequest request(String category, List<String> colors, List<String> sizes) {
        CatalogProductEntryRequest request = new CatalogProductEntryRequest();
        request.setBrandId(11L);
        request.setBaseProductName("Primer");
        request.setCategory(category);
        request.setUnitOfMeasure("LITER");
        request.setHsnCode("320910");
        request.setGstRate(new BigDecimal("18.00"));
        request.setBasePrice(new BigDecimal("1200.00"));
        request.setMinDiscountPercent(BigDecimal.ZERO);
        request.setMinSellingPrice(new BigDecimal("1100.00"));
        request.setColors(colors);
        request.setSizes(sizes);
        request.setMetadata(Map.of("productType", "decorative"));
        return request;
    }

    private BulkVariantRequest bulkVariantRequest(List<String> colors, List<String> sizes) {
        return new BulkVariantRequest(
                11L,
                null,
                null,
                "Primer",
                "RAW_MATERIAL",
                colors,
                sizes,
                null,
                "LITER",
                null,
                new BigDecimal("1200.00"),
                new BigDecimal("18.00"),
                BigDecimal.ZERO,
                new BigDecimal("1100.00"),
                Map.of("productType", "decorative")
        );
    }

    private String canonicalSku(String baseProductName, String color, String size) {
        return String.join("-", "BBR", "PRIMER", color, size);
    }

    private ProductionProduct existingProduct(String sku) {
        return existingProduct(sku, null);
    }

    private ProductionProduct existingProduct(String sku, String productName) {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(sku);
        product.setProductName(productName);
        return product;
    }

    private Account account(Long id) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
