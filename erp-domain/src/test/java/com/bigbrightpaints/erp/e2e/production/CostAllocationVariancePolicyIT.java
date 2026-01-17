package com.bigbrightpaints.erp.e2e.production;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationRequest;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.CostAllocationService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Cost allocation variance policy")
public class CostAllocationVariancePolicyIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "WE-VAR";

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
    @Autowired private ProductionLogService productionLogService;
    @Autowired private PackingService packingService;
    @Autowired private CostAllocationService costAllocationService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;

    private Company company;
    private Account wip;
    private Account rmInventory;
    private Account packagingInventory;
    private Account fgInventory;
    private Account cogs;
    private Account revenue;
    private Account discount;
    private Account tax;
    private Account laborApplied;
    private Account overheadApplied;

    @BeforeEach
    void init() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        wip = ensureAccount("WIP", AccountType.ASSET);
        rmInventory = ensureAccount("INV-RM", AccountType.ASSET);
        packagingInventory = ensureAccount("INV-PACK", AccountType.ASSET);
        fgInventory = ensureAccount("INV-FG", AccountType.ASSET);
        cogs = ensureAccount("COGS", AccountType.COGS);
        revenue = ensureAccount("REV", AccountType.REVENUE);
        discount = ensureAccount("DISC", AccountType.EXPENSE);
        tax = ensureAccount("TAX", AccountType.LIABILITY);
        laborApplied = ensureAccount("LABOR-APPLIED", AccountType.EXPENSE);
        overheadApplied = ensureAccount("OVERHEAD-APPLIED", AccountType.EXPENSE);
    }

    @AfterEach
    void cleanupContext() {
        CompanyContextHolder.clear();
    }

    @Test
    @DisplayName("Variance allocation is a no-op when applied equals actual")
    void varianceAllocationNoOpWhenAppliedMatchesActual() {
        ProductionBrand brand = createBrand("VAR-BRAND");
        ProductionProduct product = createProduct("VAR-PROD", "Variance Product", brand);

        RawMaterial base = createRawMaterial("RM-BASE", rmInventory, new BigDecimal("100"));
        RawMaterial bucket = createRawMaterial("RM-BUCKET-5L", packagingInventory, new BigDecimal("10"));
        addBatch(base, new BigDecimal("100"), new BigDecimal("10"));
        addBatch(bucket, new BigDecimal("10"), new BigDecimal("5"));
        mapPackagingSize("5L", bucket);

        ProductionLogDetailDto log = productionLogService.createLog(new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("10"),
                "L",
                new BigDecimal("10"),
                LocalDate.now().toString(),
                "VAR-TEST",
                "Supervisor",
                true,
                null,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(),
                        new BigDecimal("10"),
                        "KG"
                ))
        ));

        packingService.recordPacking(new PackingRequest(
                log.id(),
                LocalDate.now(),
                "Packer",
                List.of(new PackingLineRequest("5L", new BigDecimal("10"), 2, null, null))
        ));

        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, product.getSkuCode()).orElseThrow();
        FinishedGoodBatch fgBatch = finishedGoodBatchRepository.findAll().stream()
                .filter(batch -> batch.getFinishedGood().getId().equals(fg.getId()))
                .findFirst()
                .orElseThrow();
        BigDecimal unitCostBefore = fgBatch.getUnitCost();

        LocalDate today = LocalDate.now();
        CostAllocationResponse response = costAllocationService.allocateCosts(new CostAllocationRequest(
                today.getYear(),
                today.getMonthValue(),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                fgInventory.getId(),
                laborApplied.getId(),
                overheadApplied.getId(),
                "Variance allocation"
        ));

        assertThat(response.journalEntryIds()).isEmpty();
        assertThat(response.totalLaborAllocated()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.totalOverheadAllocated()).isEqualByComparingTo(BigDecimal.ZERO);

        FinishedGoodBatch refreshed = finishedGoodBatchRepository.findById(fgBatch.getId()).orElseThrow();
        assertThat(refreshed.getUnitCost()).isEqualByComparingTo(unitCostBefore);
    }

    private Account ensureAccount(String code, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account a = new Account();
                    a.setCompany(company);
                    a.setCode(code);
                    a.setName(code);
                    a.setType(type);
                    return accountRepository.save(a);
                });
    }

    private ProductionBrand createBrand(String code) {
        return productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    ProductionBrand b = new ProductionBrand();
                    b.setCompany(company);
                    b.setCode(code);
                    b.setName(code);
                    return productionBrandRepository.save(b);
                });
    }

    private ProductionProduct createProduct(String sku, String name, ProductionBrand brand) {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(sku);
        product.setProductName(name);
        product.setCategory("FINISHED_GOOD");
        product.setUnitOfMeasure("L");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", wip.getId());
        metadata.put("semiFinishedAccountId", fgInventory.getId());
        metadata.put("fgValuationAccountId", fgInventory.getId());
        metadata.put("fgCogsAccountId", cogs.getId());
        metadata.put("fgRevenueAccountId", revenue.getId());
        metadata.put("fgDiscountAccountId", discount.getId());
        metadata.put("fgTaxAccountId", tax.getId());
        metadata.put("laborAppliedAccountId", laborApplied.getId());
        metadata.put("overheadAppliedAccountId", overheadApplied.getId());
        product.setMetadata(metadata);
        return productionProductRepository.save(product);
    }

    private RawMaterial createRawMaterial(String sku, Account inventoryAccount, BigDecimal stock) {
        RawMaterial rm = new RawMaterial();
        rm.setCompany(company);
        rm.setSku(sku);
        rm.setName(sku);
        rm.setUnitType("KG");
        rm.setInventoryAccountId(inventoryAccount.getId());
        rm.setCurrentStock(stock);
        return rawMaterialRepository.save(rm);
    }

    private void addBatch(RawMaterial rm, BigDecimal quantity, BigDecimal costPerUnit) {
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(rm);
        batch.setBatchCode(rm.getSku() + "-B1");
        batch.setQuantity(quantity);
        batch.setUnit(Optional.ofNullable(rm.getUnitType()).orElse("UNIT"));
        batch.setCostPerUnit(costPerUnit);
        rawMaterialBatchRepository.save(batch);
    }

    private void mapPackagingSize(String size, RawMaterial material) {
        PackagingSizeMapping mapping = new PackagingSizeMapping();
        mapping.setCompany(company);
        mapping.setPackagingSize(size);
        mapping.setRawMaterial(material);
        mapping.setUnitsPerPack(1);
        mapping.setLitersPerUnit(new BigDecimal(size.replace("L", "")));
        mapping.setActive(true);
        packagingSizeMappingRepository.save(mapping);
    }
}
