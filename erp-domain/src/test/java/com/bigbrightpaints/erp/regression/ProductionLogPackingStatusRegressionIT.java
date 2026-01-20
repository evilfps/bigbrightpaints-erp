package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.dto.UnpackedBatchDto;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Packing status refresh after packing record")
class ProductionLogPackingStatusRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-013";

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionBrandRepository brandRepository;
    @Autowired private ProductionProductRepository productRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
    @Autowired private ProductionLogRepository productionLogRepository;
    @Autowired private ProductionLogService productionLogService;
    @Autowired private PackingService packingService;

    private Company company;
    private Account rmInventory;
    private Account wipAccount;
    private Account fgInventory;
    private Account cogsAccount;
    private Account revenueAccount;
    private Account discountAccount;
    private Account taxAccount;
    private Account packagingInventory;
    private ProductionBrand brand;
    private ProductionProduct product;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);

        rmInventory = ensureAccount("INV-RM", "Raw Material Inventory", AccountType.ASSET);
        packagingInventory = ensureAccount("INV-PACK", "Packaging Inventory", AccountType.ASSET);
        fgInventory = ensureAccount("INV-FG", "Finished Goods Inventory", AccountType.ASSET);
        wipAccount = ensureAccount("WIP", "Work in Progress", AccountType.ASSET);
        cogsAccount = ensureAccount("COGS", "COGS", AccountType.COGS);
        revenueAccount = ensureAccount("REV", "Revenue", AccountType.REVENUE);
        discountAccount = ensureAccount("DISC", "Discount", AccountType.EXPENSE);
        taxAccount = ensureAccount("TAX", "Tax", AccountType.LIABILITY);

        brand = ensureBrand();
        product = ensureProduct();
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void packingUpdatesStatusAndUnpackedList() {
        RawMaterial material = createRawMaterial("RM-LF013", rmInventory.getId(), MaterialType.PRODUCTION);
        createBatch(material, new BigDecimal("50"), new BigDecimal("10.00"));

        RawMaterial packaging = createRawMaterial("PACK-1L", packagingInventory.getId(), MaterialType.PACKAGING);
        createBatch(packaging, new BigDecimal("50"), new BigDecimal("1.00"));
        ensurePackagingMapping(packaging, "1L", BigDecimal.ONE);

        ProductionLogDetailDto log = productionLogService.createLog(new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("10"),
                "L",
                new BigDecimal("10"),
                LocalDate.now().toString(),
                null,
                "LF-013 Test",
                true,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new ProductionLogRequest.MaterialUsageRequest(
                        material.getId(),
                        new BigDecimal("5"),
                        "KG"
                ))
        ));

        ProductionLogDetailDto packed = packingService.recordPacking(new PackingRequest(
                log.id(),
                LocalDate.now(),
                "Packer",
                List.of(new PackingLineRequest("1L", new BigDecimal("4"), 4, null, null))
        ));

        assertThat(packed.status()).isEqualTo("PARTIAL_PACKED");
        assertThat(packed.totalPackedQuantity()).isEqualByComparingTo(new BigDecimal("4"));

        ProductionLog refreshed = productionLogRepository.findById(log.id()).orElseThrow();
        assertThat(refreshed.getStatus().name()).isEqualTo("PARTIAL_PACKED");
        assertThat(refreshed.getTotalPackedQuantity()).isEqualByComparingTo(new BigDecimal("4"));

        List<UnpackedBatchDto> batches = packingService.listUnpackedBatches();
        assertThat(batches).anyMatch(batch -> batch.id().equals(log.id())
                && "PARTIAL_PACKED".equals(batch.status()));
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

    private ProductionBrand ensureBrand() {
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setCode("BR-" + UUID.randomUUID());
        brand.setName("LF-013 Brand");
        return brandRepository.save(brand);
    }

    private ProductionProduct ensureProduct() {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode("SKU-" + UUID.randomUUID());
        product.setProductName("LF-013 Product");
        product.setCategory("FINISHED_GOOD");
        product.setUnitOfMeasure("L");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", wipAccount.getId());
        metadata.put("semiFinishedAccountId", fgInventory.getId());
        metadata.put("fgValuationAccountId", fgInventory.getId());
        metadata.put("fgCogsAccountId", cogsAccount.getId());
        metadata.put("fgRevenueAccountId", revenueAccount.getId());
        metadata.put("fgDiscountAccountId", discountAccount.getId());
        metadata.put("fgTaxAccountId", taxAccount.getId());
        product.setMetadata(metadata);

        return productRepository.save(product);
    }

    private RawMaterial createRawMaterial(String sku, Long inventoryAccountId, MaterialType materialType) {
        RawMaterial rm = new RawMaterial();
        rm.setCompany(company);
        rm.setSku(sku);
        rm.setName(sku);
        rm.setUnitType("UNIT");
        rm.setMaterialType(materialType);
        rm.setInventoryAccountId(inventoryAccountId);
        rm.setCurrentStock(BigDecimal.ZERO);
        return rawMaterialRepository.save(rm);
    }

    private void createBatch(RawMaterial material, BigDecimal quantity, BigDecimal costPerUnit) {
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        batch.setBatchCode("BATCH-" + material.getSku() + "-" + System.currentTimeMillis());
        batch.setQuantity(quantity);
        batch.setUnit(material.getUnitType());
        batch.setCostPerUnit(costPerUnit);
        batch.setReceivedAt(Instant.now());
        rawMaterialBatchRepository.save(batch);

        material.setCurrentStock(material.getCurrentStock().add(quantity));
        rawMaterialRepository.save(material);
    }

    private void ensurePackagingMapping(RawMaterial packagingMaterial, String size, BigDecimal litersPerUnit) {
        PackagingSizeMapping mapping = new PackagingSizeMapping();
        mapping.setCompany(company);
        mapping.setPackagingSize(size);
        mapping.setRawMaterial(packagingMaterial);
        mapping.setUnitsPerPack(1);
        mapping.setLitersPerUnit(litersPerUnit);
        mapping.setActive(true);
        packagingSizeMappingRepository.save(mapping);
    }
}
