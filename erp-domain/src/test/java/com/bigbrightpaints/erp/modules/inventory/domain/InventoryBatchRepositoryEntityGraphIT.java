package com.bigbrightpaints.erp.modules.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Persistence;

@Transactional
class InventoryBatchRepositoryEntityGraphIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "BHYDRATE";

  @Autowired private EntityManager entityManager;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

  @BeforeEach
  void setUp() {
    dataSeeder.ensureUser(
        "batch.hydration.accounting@bbp.com",
        "changeme",
        "Batch Hydration Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
  }

  @Test
  void findByFinishedGoodCompanyAndId_eagerlyLoadsFinishedGood() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setCompany(company);
    finishedGood.setProductCode("FG-HYDRATE-" + UUID.randomUUID());
    finishedGood.setName("Hydrated Finished Good");
    finishedGood.setUnit("UNIT");
    finishedGood.setCurrentStock(new BigDecimal("5.00"));
    finishedGood.setReservedStock(BigDecimal.ZERO);
    finishedGood = finishedGoodRepository.save(finishedGood);

    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode("FG-BATCH-" + UUID.randomUUID());
    batch.setQuantityTotal(new BigDecimal("5.00"));
    batch.setQuantityAvailable(new BigDecimal("5.00"));
    batch.setUnitCost(new BigDecimal("10.00"));
    batch = finishedGoodBatchRepository.save(batch);

    entityManager.flush();
    entityManager.clear();

    Company refreshedCompany = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    FinishedGoodBatch loaded =
        finishedGoodBatchRepository
            .findByFinishedGood_CompanyAndId(refreshedCompany, batch.getId())
            .orElseThrow();

    assertThat(Persistence.getPersistenceUtil().isLoaded(loaded, "finishedGood")).isTrue();
    assertThat(loaded.getFinishedGood().getProductCode()).isEqualTo(finishedGood.getProductCode());
    assertThat(loaded.getFinishedGood().getName()).isEqualTo("Hydrated Finished Good");
  }

  @Test
  void findByRawMaterialCompanyAndId_eagerlyLoadsRawMaterial() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setCompany(company);
    rawMaterial.setName("Hydrated Raw Material");
    rawMaterial.setSku("RM-HYDRATE-" + UUID.randomUUID());
    rawMaterial.setUnitType("KG");
    rawMaterial.setCurrentStock(new BigDecimal("20.00"));
    rawMaterial = rawMaterialRepository.save(rawMaterial);

    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rawMaterial);
    batch.setBatchCode("RM-BATCH-" + UUID.randomUUID());
    batch.setQuantity(new BigDecimal("20.00"));
    batch.setUnit("KG");
    batch.setCostPerUnit(new BigDecimal("2.50"));
    batch = rawMaterialBatchRepository.save(batch);

    entityManager.flush();
    entityManager.clear();

    Company refreshedCompany = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    RawMaterialBatch loaded =
        rawMaterialBatchRepository
            .findByRawMaterial_CompanyAndId(refreshedCompany, batch.getId())
            .orElseThrow();

    assertThat(Persistence.getPersistenceUtil().isLoaded(loaded, "rawMaterial")).isTrue();
    assertThat(loaded.getRawMaterial().getSku()).isEqualTo(rawMaterial.getSku());
    assertThat(loaded.getRawMaterial().getName()).isEqualTo("Hydrated Raw Material");
  }
}
