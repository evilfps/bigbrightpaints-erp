package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
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
        @SuppressWarnings("unchecked")
        Set<String> single = (Set<String>) ReflectionTestUtils.invokeMethod(service, "singleVariantSet", "WHITE");
        @SuppressWarnings("unchecked")
        Set<String> empty = (Set<String>) ReflectionTestUtils.invokeMethod(service, "singleVariantSet", "   ");

        assertThat(fullMatrixVariantGroupId).isEqualTo(subsetVariantGroupId);
        assertThat(fullMatrixVariantGroupId).isEqualTo(reorderedVariantGroupId);
        assertThat(differentFamilyVariantGroupId).isNotEqualTo(fullMatrixVariantGroupId);
        assertThat(single).containsExactly("WHITE");
        assertThat(empty).isEmpty();
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

    private String canonicalSku(String baseProductName, String color, String size) {
        return String.join("-", "BBR", "PRIMER", color, size);
    }

    private ProductionProduct existingProduct(String sku) {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(sku);
        return product;
    }
}
