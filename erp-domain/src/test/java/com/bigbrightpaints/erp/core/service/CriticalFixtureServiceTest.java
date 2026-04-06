package com.bigbrightpaints.erp.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.fixture.E2eFixtureCatalog;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class CriticalFixtureServiceTest {

  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private AccountRepository accountRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private ProductionBrandRepository brandRepository;
  @Mock private ProductionProductRepository productRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private CompanyRepository companyRepository;

  private CriticalFixtureService fixtureService;

  @BeforeEach
  void setUp() {
    fixtureService =
        new CriticalFixtureService(
            accountingPeriodService,
            accountRepository,
            dealerRepository,
            supplierRepository,
            brandRepository,
            productRepository,
            finishedGoodRepository,
            finishedGoodBatchRepository,
            companyRepository);

    org.mockito.Mockito.lenient()
        .when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));
    org.mockito.Mockito.lenient()
        .when(dealerRepository.save(any(Dealer.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Dealer.class));
    org.mockito.Mockito.lenient()
        .when(supplierRepository.save(any(Supplier.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Supplier.class));
    org.mockito.Mockito.lenient()
        .when(productRepository.save(any(ProductionProduct.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, ProductionProduct.class));
    org.mockito.Mockito.lenient()
        .when(
            finishedGoodBatchRepository.existsByFinishedGoodAndBatchCodeIgnoreCase(
                any(), anyString()))
        .thenReturn(true);
  }

  @Test
  void criticalFixtureService_isLoadedFromTestClasspath() {
    String classLocation =
        CriticalFixtureService.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toExternalForm();
    String testClassLocation =
        CriticalFixtureServiceTest.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toExternalForm();

    assertThat(classLocation).isEqualTo(testClassLocation);
  }

  @Test
  void seedCompanyFixtures_preservesExistingProductCommercialFields() {
    Company company = new Company();
    company.setCode("ACME");
    company.setName("Acme Paints");
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");

    Map<String, Account> accounts = accounts(company);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(accounts.get(invocation.getArgument(1, String.class))));

    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode("FIX-DEALER");
    dealer.setName("Fixture Dealer");
    dealer.setStatus("ACTIVE");
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER"))
        .thenReturn(Optional.of(dealer));

    Supplier supplier = new Supplier();
    supplier.setCompany(company);
    supplier.setCode("FIX-SUP");
    supplier.setName("Fixture Supplier");
    supplier.setStatus("ACTIVE");
    when(supplierRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-SUP"))
        .thenReturn(Optional.of(supplier));

    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setCode("FIX-BRAND");
    brand.setName("Fixture Brand");
    when(brandRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-BRAND"))
        .thenReturn(Optional.of(brand));

    ProductionProduct fixtureProduct =
        product(company, brand, "FG-FIXTURE", "Fixture Finished Good", "100.00", "18");
    ProductionProduct existingProduct =
        product(
            company,
            brand,
            E2eFixtureCatalog.ORDER_PRIMARY_SKU,
            "Edited Primary Name",
            "999.99",
            "5");
    existingProduct.setMetadata(new HashMap<>(Map.of("customFlag", true)));

    when(productRepository.findByCompanyAndSkuCode(eq(company), anyString()))
        .thenAnswer(
            invocation -> {
              String sku = invocation.getArgument(1, String.class);
              if ("FG-FIXTURE".equals(sku)) {
                return Optional.of(fixtureProduct);
              }
              if (E2eFixtureCatalog.ORDER_PRIMARY_SKU.equals(sku)) {
                return Optional.of(existingProduct);
              }
              return Optional.empty();
            });

    FinishedGood fixtureFinishedGood = finishedGood(company, 11L, "FG-FIXTURE");
    FinishedGood orderFinishedGood =
        finishedGood(company, 12L, E2eFixtureCatalog.ORDER_PRIMARY_SKU);
    when(finishedGoodRepository.findByCompanyAndProductCode(eq(company), anyString()))
        .thenAnswer(
            invocation -> {
              String productCode = invocation.getArgument(1, String.class);
              if ("FG-FIXTURE".equals(productCode)) {
                return Optional.of(fixtureFinishedGood);
              }
              if (E2eFixtureCatalog.ORDER_PRIMARY_SKU.equals(productCode)) {
                return Optional.of(orderFinishedGood);
              }
              return Optional.empty();
            });

    fixtureService.seedCompanyFixtures(company);

    assertThat(existingProduct.getProductName()).isEqualTo("Edited Primary Name");
    assertThat(existingProduct.getBasePrice()).isEqualByComparingTo("999.99");
    assertThat(existingProduct.getGstRate()).isEqualByComparingTo("5");
    assertThat(existingProduct.getMetadata())
        .containsEntry("customFlag", true)
        .containsEntry("wipAccountId", accounts.get("WIP").getId())
        .containsEntry("fgValuationAccountId", accounts.get("INV").getId())
        .containsEntry("fgCogsAccountId", accounts.get("COGS").getId())
        .containsEntry("fgRevenueAccountId", accounts.get("REV").getId())
        .containsEntry("fgDiscountAccountId", accounts.get("DISC").getId())
        .containsEntry("fgTaxAccountId", accounts.get("GST-OUT").getId());
  }

  private Map<String, Account> accounts(Company company) {
    return Map.of(
        "CASH", account(company, 1L, "CASH", AccountType.ASSET),
        "AR", account(company, 2L, "AR", AccountType.ASSET),
        "AP", account(company, 3L, "AP", AccountType.LIABILITY),
        "INV", account(company, 4L, "INV", AccountType.ASSET),
        "COGS", account(company, 5L, "COGS", AccountType.COGS),
        "REV", account(company, 6L, "REV", AccountType.REVENUE),
        "GST-OUT", account(company, 7L, "GST-OUT", AccountType.LIABILITY),
        "GST-IN", account(company, 8L, "GST-IN", AccountType.ASSET),
        "DISC", account(company, 9L, "DISC", AccountType.EXPENSE),
        "WIP", account(company, 10L, "WIP", AccountType.ASSET));
  }

  private Account account(Company company, Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setName(code);
    account.setType(type);
    account.setBalance(BigDecimal.ZERO);
    return account;
  }

  private ProductionProduct product(
      Company company,
      ProductionBrand brand,
      String sku,
      String productName,
      String basePrice,
      String gstRate) {
    ProductionProduct product = new ProductionProduct();
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode(sku);
    product.setProductName(productName);
    product.setCategory("FINISHED_GOOD");
    product.setUnitOfMeasure("UNIT");
    product.setBasePrice(new BigDecimal(basePrice));
    product.setGstRate(new BigDecimal(gstRate));
    product.setMetadata(new HashMap<>());
    return product;
  }

  private FinishedGood finishedGood(Company company, Long id, String productCode) {
    FinishedGood finishedGood = new FinishedGood();
    ReflectionTestUtils.setField(finishedGood, "id", id);
    finishedGood.setCompany(company);
    finishedGood.setProductCode(productCode);
    finishedGood.setName(productCode);
    finishedGood.setUnit("UNIT");
    finishedGood.setCurrentStock(BigDecimal.ZERO);
    finishedGood.setReservedStock(BigDecimal.ZERO);
    return finishedGood;
  }
}
