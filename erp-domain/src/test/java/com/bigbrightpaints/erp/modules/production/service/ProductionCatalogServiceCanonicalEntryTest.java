package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
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
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
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
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private SkuReadinessService skuReadinessService;
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
                skuReadinessService,
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
        request.setBaseProductName("P".repeat(130));

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
        String existingSku = canonicalSku("RAW_MATERIAL", "Primer", "BLUE", "1L");
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
        String conflictingSku = canonicalSku("RAW_MATERIAL", "Primer", "BLUE", "1L");
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
    void createOrPreviewCatalogProducts_previewIncludesReadinessOnGeneratedMembers() {
        SkuReadinessDto readiness = new SkuReadinessDto(
                "RM-PRIMER-WHITE-1L",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"))
        );
        when(skuReadinessService.forPlannedProduct(
                any(ProductionProduct.class),
                eq(SkuReadinessService.ExpectedStockType.RAW_MATERIAL),
                any(),
                any()
        )).thenReturn(readiness);

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(
                request("RAW_MATERIAL", List.of("WHITE"), List.of("1L")),
                true);

        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().sku()).isEqualTo("RM-PRIMER-WHITE-1L");
        assertThat(response.members().getFirst().readiness()).isEqualTo(readiness);
    }

    @Test
    void createOrPreviewCatalogProducts_previewEnrichesFinishedGoodsWithPostingDefaults() {
        when(companyDefaultAccountsService.getDefaults()).thenReturn(
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
    void createOrPreviewCatalogProducts_previewReturnsFinishedGoodReadinessWhenDefaultsAreMissing() {
        when(companyDefaultAccountsService.getDefaults()).thenReturn(
                new CompanyDefaultAccountsService.DefaultAccounts(null, null, null, null, null));
        SkuReadinessDto readiness = new SkuReadinessDto(
                "FG-PRIMER-WHITE-1L",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED", "WIP_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED"))
        );
        when(skuReadinessService.forPlannedProduct(
                any(ProductionProduct.class),
                eq(SkuReadinessService.ExpectedStockType.FINISHED_GOOD),
                any(),
                any()
        )).thenReturn(readiness);

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(
                request("FINISHED_GOOD", List.of("WHITE"), List.of("1L")),
                true);

        assertThat(response.preview()).isTrue();
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().sku()).isEqualTo("FG-PRIMER-WHITE-1L");
        assertThat(response.members().getFirst().readiness()).isEqualTo(readiness);
        assertThat(response.metadata()).doesNotContainKeys(
                "fgValuationAccountId",
                "fgCogsAccountId",
                "fgRevenueAccountId",
                "fgTaxAccountId");
    }

    @Test
    void createOrPreviewCatalogProducts_previewPassesGstRateIntoFinishedGoodReadinessDraft() {
        when(companyDefaultAccountsService.getDefaults()).thenReturn(
                new CompanyDefaultAccountsService.DefaultAccounts(null, null, null, null, null));
        SkuReadinessDto readiness = new SkuReadinessDto(
                "RM-PRIMER-WHITE-1L",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED", "WIP_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("GST_OUTPUT_ACCOUNT_MISSING"))
        );
        when(skuReadinessService.forPlannedProduct(
                argThat(product -> product.getGstRate() != null
                        && product.getGstRate().compareTo(new BigDecimal("18.00")) == 0),
                eq(SkuReadinessService.ExpectedStockType.FINISHED_GOOD),
                any(),
                any()
        )).thenReturn(readiness);

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(
                request("FINISHED_GOOD", List.of("WHITE"), List.of("1L")),
                true);

        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().readiness()).isEqualTo(readiness);
    }

    @Test
    void createOrPreviewCatalogProducts_createIncludesReadinessOnCreatedMembers() {
        company.setDefaultInventoryAccountId(9001L);
        when(companyEntityLookup.requireAccount(company, 9001L)).thenReturn(account(9001L));
        when(productRepository.findByCompanyAndSkuCode(company, "RM-PRIMER-WHITE-1L")).thenReturn(Optional.empty());
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> {
            ProductionProduct saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 901L);
            ReflectionTestUtils.setField(saved, "publicId", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            return saved;
        });
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-PRIMER-WHITE-1L")).thenReturn(Optional.empty());
        when(rawMaterialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SkuReadinessDto readiness = new SkuReadinessDto(
                "RM-PRIMER-WHITE-1L",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"))
        );
        when(skuReadinessService.forSku(
                company,
                "RM-PRIMER-WHITE-1L",
                SkuReadinessService.ExpectedStockType.RAW_MATERIAL
        )).thenReturn(readiness);

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(
                request("RAW_MATERIAL", List.of("WHITE"), List.of("1L")),
                false);

        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().sku()).isEqualTo("RM-PRIMER-WHITE-1L");
        assertThat(response.members().getFirst().readiness()).isEqualTo(readiness);
    }

    @Test
    void createOrPreviewCatalogProducts_preservesPackagingItemClassThroughCreate() {
        company.setDefaultInventoryAccountId(9001L);
        when(companyEntityLookup.requireAccount(company, 9001L)).thenReturn(account(9001L));
        when(productRepository.findByCompanyAndSkuCode(company, "PKG-PRIMER-WHITE-1L")).thenReturn(Optional.empty());
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> {
            ProductionProduct saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 902L);
            ReflectionTestUtils.setField(saved, "publicId", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
            return saved;
        });
        when(rawMaterialRepository.findByCompanyAndSku(company, "PKG-PRIMER-WHITE-1L")).thenReturn(Optional.empty());
        final com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial[] savedMaterial = new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial[1];
        when(rawMaterialRepository.save(any())).thenAnswer(invocation -> {
            savedMaterial[0] = invocation.getArgument(0);
            return savedMaterial[0];
        });

        SkuReadinessDto readiness = new SkuReadinessDto(
                "PKG-PRIMER-WHITE-1L",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"))
        );
        when(skuReadinessService.forSku(
                company,
                "PKG-PRIMER-WHITE-1L",
                SkuReadinessService.ExpectedStockType.PACKAGING_RAW_MATERIAL
        )).thenReturn(readiness);

        CatalogProductEntryResponse response = service.createOrPreviewCatalogProducts(
                request("PACKAGING_RAW_MATERIAL", List.of("WHITE"), List.of("1L")),
                false);

        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().sku()).isEqualTo("PKG-PRIMER-WHITE-1L");
        assertThat(response.members().getFirst().itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(savedMaterial[0]).isNotNull();
        assertThat(savedMaterial[0].getMaterialType())
                .isEqualTo(com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PACKAGING);
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
        String conflictingSku = canonicalSku("RAW_MATERIAL", "Primer", "WHITE", "1L");
        SkuReadinessDto readiness = new SkuReadinessDto(
                canonicalSku("RAW_MATERIAL", "Primer", "BLUE", "1L"),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"))
        );
        when(skuReadinessService.forPlannedProduct(
                any(ProductionProduct.class),
                eq(SkuReadinessService.ExpectedStockType.RAW_MATERIAL),
                any(),
                any()
        )).thenReturn(readiness);
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
                            .containsExactly(canonicalSku("RAW_MATERIAL", "Primer", "BLUE", "1L"));
                    assertThat(wouldCreate.getFirst().readiness()).isEqualTo(readiness);
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
                "RM-PRIMER-WHITE-1L",
                "MANUAL_OVERRIDE",
                "Primer WHITE 1L",
                "RAW_MATERIAL",
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
                .containsExactly("RM-PRIMER-WHITE-1L");
        assertThat(response.wouldCreate())
                .extracting(BulkVariantResponse.VariantItem::size)
                .containsExactly("1L");
    }

    @Test
    void updateProduct_itemClassHint_reclassifiesRawMaterialMirrorAsPackaging() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 41L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode("RM-PRIMER");
        product.setProductName("Primer");
        product.setCategory("RAW_MATERIAL");
        product.setUnitOfMeasure("KG");
        product.setMetadata(Map.of());

        RawMaterial material = new RawMaterial();
        ReflectionTestUtils.setField(material, "id", 42L);
        material.setCompany(company);
        material.setSku("RM-PRIMER");
        material.setName("Primer");
        material.setUnitType("KG");
        material.setInventoryAccountId(999L);
        material.setMaterialType(MaterialType.PRODUCTION);

        when(companyEntityLookup.requireProductionProduct(company, 41L)).thenReturn(product);
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-PRIMER")).thenReturn(Optional.of(material));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "RM-PRIMER")).thenReturn(Optional.of(material));
        when(rawMaterialRepository.save(any(RawMaterial.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductionProductDto response = service.updateProduct(
                41L,
                new ProductUpdateRequest(
                        "Primer",
                        null,
                        "PACKAGING_RAW_MATERIAL",
                        null,
                        null,
                        "KG",
                        new BigDecimal("100.00"),
                        new BigDecimal("18.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("95.00"),
                        Map.of()));

        assertThat(response.category()).isEqualTo("RAW_MATERIAL");
        assertThat(product.getCategory()).isEqualTo("RAW_MATERIAL");
        assertThat(material.getMaterialType()).isEqualTo(MaterialType.PACKAGING);
    }

    @Test
    void syncRawMaterial_returnsFalseForFinishedGoods() {
        ProductionProduct finishedGood = new ProductionProduct();
        finishedGood.setCompany(company);
        finishedGood.setCategory("FINISHED_GOOD");
        finishedGood.setSkuCode("FG-PRIMER");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "syncRawMaterial", company, finishedGood, "FINISHED_GOOD"))
                .isFalse();
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

    @Test
    void canonicalHelperMethods_coverItemClassAndMaterialTypeMappings() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "containsAnyPackagingToken", "pack liner"))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "containsAnyPackagingToken", "container shell"))
                .isTrue();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "FINISHED_GOOD"))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "RAW_MATERIAL"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "PACKAGING_RAW_MATERIAL"))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "PACKAGING"))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeVariantItemClass", "RAW_MATERIAL"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeVariantItemClass", "PACKAGING"))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeVariantItemClass", "FINISHED_GOOD"))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassSkuPrefix", "FINISHED_GOOD"))
                .isEqualTo("FG");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassSkuPrefix", "RAW_MATERIAL"))
                .isEqualTo("RM");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassSkuPrefix", "PACKAGING_RAW_MATERIAL"))
                .isEqualTo("PKG");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "categoryForItemClass", "PACKAGING_RAW_MATERIAL"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "categoryForItemClass", "RAW_MATERIAL"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "categoryForItemClass", "FINISHED_GOOD"))
                .isEqualTo("FINISHED_GOOD");
        assertThat((SkuReadinessService.ExpectedStockType) ReflectionTestUtils.invokeMethod(service, "expectedStockType", "FINISHED_GOOD"))
                .isEqualTo(SkuReadinessService.ExpectedStockType.FINISHED_GOOD);
        assertThat((SkuReadinessService.ExpectedStockType) ReflectionTestUtils.invokeMethod(service, "expectedStockType", "RAW_MATERIAL"))
                .isEqualTo(SkuReadinessService.ExpectedStockType.RAW_MATERIAL);
        assertThat((SkuReadinessService.ExpectedStockType) ReflectionTestUtils.invokeMethod(service, "expectedStockType", "PACKAGING_RAW_MATERIAL"))
                .isEqualTo(SkuReadinessService.ExpectedStockType.PACKAGING_RAW_MATERIAL);
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "buildDeterministicSku", "PACKAGING_RAW_MATERIAL", "PRIMER", "WHITE", "1L"))
                .isEqualTo("PKG-PRIMER-WHITE-1L");

        RawMaterial packagingMaterial = new RawMaterial();
        packagingMaterial.setMaterialType(MaterialType.PACKAGING);
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-001")).thenReturn(Optional.of(packagingMaterial));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "RM-001")).thenReturn(Optional.empty());

        ProductionProduct packagingProduct = new ProductionProduct();
        packagingProduct.setCompany(company);
        packagingProduct.setCategory("RAW_MATERIAL");
        packagingProduct.setSkuCode("PKG-001");
        packagingProduct.setProductName("Bottle");

        ProductionProduct rawProduct = new ProductionProduct();
        rawProduct.setCompany(company);
        rawProduct.setCategory("RAW_MATERIAL");
        rawProduct.setSkuCode("RM-001");
        rawProduct.setProductName("Resin");

        ProductionProduct rawProductWithoutSku = new ProductionProduct();
        rawProductWithoutSku.setCompany(company);
        rawProductWithoutSku.setCategory("RAW_MATERIAL");
        rawProductWithoutSku.setProductName("Base Resin");

        ProductionProduct finishedGood = new ProductionProduct();
        finishedGood.setCategory("FINISHED_GOOD");

        RawMaterial existingProductionMaterial = new RawMaterial();
        existingProductionMaterial.setMaterialType(MaterialType.PRODUCTION);
        RawMaterial existingPackagingMaterial = new RawMaterial();
        existingPackagingMaterial.setMaterialType(MaterialType.PACKAGING);
        RawMaterial heuristicPackagingMaterial = new RawMaterial();
        heuristicPackagingMaterial.setMaterialType(null);
        RawMaterial heuristicProductionMaterial = new RawMaterial();
        heuristicProductionMaterial.setMaterialType(null);

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", packagingProduct))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", rawProduct))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", rawProductWithoutSku))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", finishedGood))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", new Object[]{null}))
                .isEqualTo("FINISHED_GOOD");
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                packagingProduct,
                null,
                "PACKAGING_RAW_MATERIAL"))
                .isEqualTo(MaterialType.PACKAGING);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                rawProduct,
                null,
                "RAW_MATERIAL"))
                .isEqualTo(MaterialType.PRODUCTION);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                rawProduct,
                existingProductionMaterial,
                null))
                .isEqualTo(MaterialType.PRODUCTION);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                rawProduct,
                existingPackagingMaterial,
                null))
                .isEqualTo(MaterialType.PACKAGING);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                packagingProduct,
                heuristicPackagingMaterial,
                null))
                .isEqualTo(MaterialType.PACKAGING);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                rawProduct,
                heuristicProductionMaterial,
                null))
                .isEqualTo(MaterialType.PRODUCTION);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "LEGACY"))
                .hasMessageContaining("itemClass is required");
    }

    private CatalogProductEntryRequest request(String itemClass, List<String> colors, List<String> sizes) {
        CatalogProductEntryRequest request = new CatalogProductEntryRequest();
        request.setBrandId(11L);
        request.setBaseProductName("Primer");
        request.setCategory("IGNORED_LEGACY_CATEGORY");
        request.setItemClass(itemClass);
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

    private String canonicalSku(String itemClass, String baseProductName, String color, String size) {
        String prefix = switch (itemClass) {
            case "RAW_MATERIAL" -> "RM";
            case "PACKAGING_RAW_MATERIAL" -> "PKG";
            default -> "FG";
        };
        return String.join("-", prefix, "PRIMER", color, size);
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
