package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialIntakeRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.raw-material.intake.enabled=true")
class CR_RawMaterialIntakeIdempotencyIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CR-RM-IDEMP";

  @Autowired private RawMaterialService rawMaterialService;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private SupplierRepository supplierRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;

  private Company company;
  private Supplier supplier;
  private RawMaterial material;

  @BeforeEach
  void setUp() {
    company =
        companyRepository
            .findByCodeIgnoreCase(COMPANY_CODE)
            .orElseGet(
                () -> {
                  Company created = new Company();
                  created.setCode(COMPANY_CODE);
                  created.setName("CR Raw Material Idempotency");
                  created.setTimezone("UTC");
                  return companyRepository.save(created);
                });
    Account inventory = ensureAccount(company, "INV-RM", "Inventory", AccountType.ASSET);
    Account payable = ensureAccount(company, "AP-RM", "Accounts Payable", AccountType.LIABILITY);
    company.setDefaultInventoryAccountId(inventory.getId());
    companyRepository.save(company);

    supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "SUP-RM")
            .orElseGet(
                () -> {
                  Supplier created = new Supplier();
                  created.setCompany(company);
                  created.setCode("SUP-RM");
                  created.setName("RM Supplier");
                  created.setStatus("ACTIVE");
                  created.setPayableAccount(payable);
                  return supplierRepository.save(created);
                });
    if (supplier.getPayableAccount() == null || !supplier.getPayableAccount().equals(payable)) {
      supplier.setPayableAccount(payable);
      supplier.setStatus("ACTIVE");
      supplier = supplierRepository.save(supplier);
    }

    material =
        rawMaterialRepository
            .findByCompanyAndSku(company, "RM-001")
            .orElseGet(
                () -> {
                  RawMaterial created = new RawMaterial();
                  created.setCompany(company);
                  created.setName("Raw Material");
                  created.setSku("RM-001");
                  created.setUnitType("KG");
                  created.setCurrentStock(BigDecimal.ZERO);
                  created.setInventoryAccountId(inventory.getId());
                  return rawMaterialRepository.save(created);
                });
    if (material.getInventoryAccountId() == null
        || !material.getInventoryAccountId().equals(inventory.getId())) {
      material.setInventoryAccountId(inventory.getId());
      material = rawMaterialRepository.save(material);
    }

    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void intakeIsIdempotentForSameKey() {
    RawMaterialIntakeRequest request =
        new RawMaterialIntakeRequest(
            material.getId(),
            null,
            new BigDecimal("5"),
            "KG",
            new BigDecimal("12.50"),
            supplier.getId(),
            "initial intake");

    RawMaterialBatchDto first = rawMaterialService.intake(request, "RM-IDEMP-001");
    RawMaterialBatchDto second = rawMaterialService.intake(request, "RM-IDEMP-001");

    assertThat(second.id()).isEqualTo(first.id());

    RawMaterialBatch batch = rawMaterialBatchRepository.findById(first.id()).orElseThrow();
    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository.findByRawMaterialBatch(batch);
    assertThat(movements).hasSize(1);
  }

  @Test
  void intakeMismatchFailsClosed() {
    RawMaterialIntakeRequest request =
        new RawMaterialIntakeRequest(
            material.getId(),
            null,
            new BigDecimal("5"),
            "KG",
            new BigDecimal("12.50"),
            supplier.getId(),
            "initial intake");

    rawMaterialService.intake(request, "RM-IDEMP-002");

    RawMaterialIntakeRequest mismatch =
        new RawMaterialIntakeRequest(
            material.getId(),
            null,
            new BigDecimal("7"),
            "KG",
            new BigDecimal("12.50"),
            supplier.getId(),
            "initial intake");

    ApplicationException ex =
        assertThrows(
            ApplicationException.class, () -> rawMaterialService.intake(mismatch, "RM-IDEMP-002"));
    assertThat(ex.getMessage()).contains("Idempotency key already used");
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }
}
