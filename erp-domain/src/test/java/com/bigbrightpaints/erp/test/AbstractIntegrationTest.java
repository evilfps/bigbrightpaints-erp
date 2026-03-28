package com.bigbrightpaints.erp.test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryValuationService;

@SpringBootTest(
    classes = TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(TestBeansConfig.class)
public abstract class AbstractIntegrationTest {

  private static final String EXTERNAL_DB_URL = resolveConfig("erp.test.db.url", "ERP_TEST_DB_URL");
  private static final String EXTERNAL_DB_USERNAME =
      resolveConfig("erp.test.db.username", "ERP_TEST_DB_USERNAME");
  private static final String EXTERNAL_DB_PASSWORD =
      resolveConfig("erp.test.db.password", "ERP_TEST_DB_PASSWORD");
  private static final boolean USE_EXTERNAL_DB =
      EXTERNAL_DB_URL != null && !EXTERNAL_DB_URL.isBlank();

  public static final PostgreSQLContainer<?> POSTGRES =
      USE_EXTERNAL_DB
          ? null
          : new PostgreSQLContainer<>("postgres:16-alpine")
              .withDatabaseName("erp_domain_test")
              .withUsername("erp_test")
              .withPassword("erp_test");

  static {
    if (!USE_EXTERNAL_DB) {
      // Ensure the container is started before Spring resolves dynamic properties.
      POSTGRES.start();
    }
  }

  @Autowired protected TestDataSeeder dataSeeder;

  @Autowired protected CompanyRepository companyRepository;
  @Autowired protected CompanyContextService companyContextService;
  @Autowired protected CompanyDefaultAccountsService companyDefaultAccountsService;
  @Autowired protected FinishedGoodRepository finishedGoodRepository;
  @Autowired protected FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired protected InventoryMovementRepository inventoryMovementRepository;
  @Autowired protected BatchNumberService batchNumberService;
  @Autowired protected InventoryValuationService inventoryValuationService;

  @DynamicPropertySource
  static void registerDataSource(DynamicPropertyRegistry registry) {
    if (USE_EXTERNAL_DB) {
      registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
      registry.add(
          "spring.datasource.username", () -> defaultString(EXTERNAL_DB_USERNAME, "erp_test"));
      registry.add(
          "spring.datasource.password", () -> defaultString(EXTERNAL_DB_PASSWORD, "erp_test"));
    } else {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration_v2");
    registry.add("spring.flyway.table", () -> "flyway_schema_history_v2");
    registry.add("spring.jpa.open-in-view", () -> true);
    // Disable AMQP/Rabbit auto-config in tests to avoid external dependency
    registry.add(
        "spring.autoconfigure.exclude",
        () ->
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration");
  }

  private static String resolveConfig(String propertyName, String envName) {
    String propertyValue = System.getProperty(propertyName);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return propertyValue;
    }
    String envValue = System.getenv(envName);
    if (envValue != null && !envValue.isBlank()) {
      return envValue;
    }
    return null;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  protected Company enableModule(String companyCode, CompanyModule module) {
    Company company =
        companyRepository
            .findByCodeIgnoreCase(companyCode)
            .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyCode));
    return enableModule(company, module);
  }

  protected Company enableModule(Company company, CompanyModule module) {
    Company managedCompany = company;
    if (company.getId() != null) {
      managedCompany = companyRepository.findById(company.getId()).orElse(company);
    } else if (company.getCode() != null && !company.getCode().isBlank()) {
      managedCompany = companyRepository.findByCodeIgnoreCase(company.getCode()).orElse(company);
    }
    Set<String> enabledModules = new LinkedHashSet<>(managedCompany.getEnabledModules());
    enabledModules.add(module.name());
    managedCompany.setEnabledModules(enabledModules);
    return companyRepository.save(managedCompany);
  }

  protected FinishedGoodDto createFinishedGoodForTest(FinishedGoodRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setCompany(company);
    finishedGood.setProductCode(request.productCode());
    finishedGood.setName(request.name());
    finishedGood.setUnit(request.unit() == null ? "UNIT" : request.unit());
    finishedGood.setCostingMethod(
        inventoryValuationService.normalizeCostingMethod(request.costingMethod()));
    finishedGood.setCurrentStock(BigDecimal.ZERO);
    finishedGood.setReservedStock(BigDecimal.ZERO);
    finishedGood.setValuationAccountId(request.valuationAccountId());
    finishedGood.setCogsAccountId(request.cogsAccountId());
    finishedGood.setRevenueAccountId(request.revenueAccountId());
    finishedGood.setDiscountAccountId(request.discountAccountId());
    finishedGood.setTaxAccountId(request.taxAccountId());
    applyDefaultAccountsIfMissing(finishedGood);
    FinishedGood saved = finishedGoodRepository.save(finishedGood);
    return toFinishedGoodDto(saved);
  }

  protected FinishedGoodBatchDto registerFinishedGoodBatchForTest(FinishedGoodBatchRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndId(company, request.finishedGoodId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Finished good not found"));

    BigDecimal quantity = inventoryValuationService.safeQuantity(request.quantity());
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Batch quantity must be greater than zero");
    }
    BigDecimal unitCost = request.unitCost() != null ? request.unitCost() : BigDecimal.ZERO;
    if (unitCost.compareTo(BigDecimal.ZERO) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Batch unit cost cannot be negative");
    }

    Instant manufacturedAt =
        request.manufacturedAt() == null
            ? com.bigbrightpaints.erp.core.util.CompanyTime.now(company)
            : request.manufacturedAt();
    LocalDate producedDate =
        request.manufacturedAt() == null
            ? null
            : LocalDate.ofInstant(
                request.manufacturedAt(),
                java.time.ZoneId.of(
                    company.getTimezone() == null || company.getTimezone().isBlank()
                        ? "UTC"
                        : company.getTimezone()));
    String batchCode =
        request.batchCode() == null || request.batchCode().isBlank()
            ? batchNumberService.nextFinishedGoodBatchCode(finishedGood, producedDate)
            : request.batchCode().trim();

    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(manufacturedAt);
    batch.setExpiryDate(request.expiryDate());
    batch.setSource(InventoryBatchSource.PRODUCTION);
    FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

    finishedGood.setCurrentStock(
        inventoryValuationService.safeQuantity(finishedGood.getCurrentStock()).add(quantity));
    finishedGoodRepository.save(finishedGood);
    inventoryValuationService.invalidateWeightedAverageCost(finishedGood.getId());

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setFinishedGoodBatch(savedBatch);
    movement.setReferenceType(InventoryReference.MANUFACTURING_ORDER);
    movement.setReferenceId(savedBatch.getPublicId().toString());
    movement.setMovementType("RECEIPT");
    movement.setQuantity(quantity);
    movement.setUnitCost(unitCost);
    inventoryMovementRepository.save(movement);

    return toFinishedGoodBatchDto(savedBatch);
  }

  private void applyDefaultAccountsIfMissing(FinishedGood finishedGood) {
    boolean needsDefaults =
        finishedGood.getValuationAccountId() == null
            || finishedGood.getCogsAccountId() == null
            || finishedGood.getRevenueAccountId() == null
            || finishedGood.getTaxAccountId() == null;
    if (!needsDefaults) {
      return;
    }
    var defaults = companyDefaultAccountsService.requireDefaults();
    if (finishedGood.getValuationAccountId() == null) {
      finishedGood.setValuationAccountId(defaults.inventoryAccountId());
    }
    if (finishedGood.getCogsAccountId() == null) {
      finishedGood.setCogsAccountId(defaults.cogsAccountId());
    }
    if (finishedGood.getRevenueAccountId() == null) {
      finishedGood.setRevenueAccountId(defaults.revenueAccountId());
    }
    if (finishedGood.getDiscountAccountId() == null && defaults.discountAccountId() != null) {
      finishedGood.setDiscountAccountId(defaults.discountAccountId());
    }
    if (finishedGood.getTaxAccountId() == null) {
      finishedGood.setTaxAccountId(defaults.taxAccountId());
    }
  }

  private FinishedGoodDto toFinishedGoodDto(FinishedGood finishedGood) {
    return new FinishedGoodDto(
        finishedGood.getId(),
        finishedGood.getPublicId(),
        finishedGood.getProductCode(),
        finishedGood.getName(),
        finishedGood.getUnit(),
        inventoryValuationService.safeQuantity(finishedGood.getCurrentStock()),
        inventoryValuationService.safeQuantity(finishedGood.getReservedStock()),
        finishedGood.getCostingMethod(),
        finishedGood.getValuationAccountId(),
        finishedGood.getCogsAccountId(),
        finishedGood.getRevenueAccountId(),
        finishedGood.getDiscountAccountId(),
        finishedGood.getTaxAccountId());
  }

  private FinishedGoodBatchDto toFinishedGoodBatchDto(FinishedGoodBatch batch) {
    return new FinishedGoodBatchDto(
        batch.getId(),
        batch.getPublicId(),
        batch.getBatchCode(),
        batch.getQuantityTotal(),
        batch.getQuantityAvailable(),
        batch.getUnitCost(),
        batch.getManufacturedAt(),
        batch.getExpiryDate());
  }
}
