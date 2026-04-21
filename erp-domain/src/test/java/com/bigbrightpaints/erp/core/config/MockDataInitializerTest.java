package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.fixture.E2eFixtureCatalog;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesFulfillmentService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;

@ExtendWith(MockitoExtension.class)
class MockDataInitializerTest {

  @Mock private RoleRepository roleRepository;
  @Mock private UserAccountRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private CompanyRepository companyRepository;
  @Mock private SalesOrderCrudService salesOrderCrudService;
  @Mock private SalesFulfillmentService salesFulfillmentService;
  @Mock private ProductionProductRepository productRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private FinishedGoodBatchRepository batchRepository;

  private MockDataInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new MockDataInitializer();
    lenient()
        .when(roleRepository.findByName(anyString()))
        .thenAnswer(invocation -> Optional.of(role(invocation.getArgument(0, String.class))));
    lenient()
        .when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, UserAccount.class));
  }

  @Test
  void seedRolesAndUsers_createsScopedMockAdminWhenMissing() {
    Company company = company("MOCK");
    when(passwordEncoder.encode("Temp123!")).thenReturn("encoded-password");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "mock-admin@example.com", "MOCK"))
        .thenReturn(Optional.empty());

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " Mock-Admin@Example.com ",
        " Temp123! ");

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    UserAccount saved = userCaptor.getValue();

    assertThat(saved.getEmail()).isEqualTo("mock-admin@example.com");
    assertThat(saved.getAuthScopeCode()).isEqualTo("MOCK");
    assertThat(saved.getCompany()).isEqualTo(company);
    assertThat(saved.isMustChangePassword()).isTrue();
    assertThat(saved.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES");
  }

  @Test
  void seedRolesAndUsers_updatesExistingScopedMockAdminWhenPresent() {
    Company company = company("MOCK");
    UserAccount existingAdmin =
        new UserAccount("legacy-mock@example.com", "OLD", "hash", "Legacy Mock");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "legacy-mock@example.com", "MOCK"))
        .thenReturn(Optional.of(existingAdmin));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " legacy-mock@example.com ",
        "");

    verify(userRepository).save(existingAdmin);
    assertThat(existingAdmin.getAuthScopeCode()).isEqualTo("MOCK");
    assertThat(existingAdmin.getCompany()).isEqualTo(company);
    assertThat(existingAdmin.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES");
  }

  @Test
  void attachMainAdmin_refreshesPersistedCompanyBeforeSaving() {
    Company staleCompany = company("MOCK");
    ReflectionTestUtils.setField(staleCompany, "id", 1L);
    Company refreshedCompany = company("MOCK");
    ReflectionTestUtils.setField(refreshedCompany, "id", 1L);
    UserAccount adminUser = new UserAccount("mock-admin@example.com", "MOCK", "hash", "Mock Admin");
    ReflectionTestUtils.setField(adminUser, "id", 42L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(refreshedCompany));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer, "attachMainAdmin", companyRepository, staleCompany, adminUser);

    verify(companyRepository).save(refreshedCompany);
    assertThat(refreshedCompany.getOnboardingAdminEmail()).isEqualTo("mock-admin@example.com");
    assertThat(refreshedCompany.getMainAdminUserId()).isEqualTo(42L);
    assertThat(refreshedCompany.getOnboardingAdminUserId()).isEqualTo(42L);
    assertThat(staleCompany.getOnboardingAdminEmail()).isNull();
  }

  @Test
  void createCompany_defaultsStateCodeForNewCompany() {
    when(companyRepository.findByCodeIgnoreCase("MOCK")).thenReturn(Optional.empty());
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));

    Company company =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            initializer, "createCompany", companyRepository);

    assertThat(company.getCode()).isEqualTo("MOCK");
    assertThat(company.getStateCode()).isEqualTo("MH");
  }

  @Test
  void createCompany_backfillsMissingStateCodeForExistingCompany() {
    Company existingCompany = company("MOCK");
    when(companyRepository.findByCodeIgnoreCase("MOCK")).thenReturn(Optional.of(existingCompany));
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));

    Company company =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            initializer, "createCompany", companyRepository);

    assertThat(company.getStateCode()).isEqualTo("MH");
  }

  @Test
  void seedReadyToConfirmOrder_skipsReservationForProgressedReplayStatus() {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 7L);
    when(salesOrderCrudService.createOrder(any()))
        .thenReturn(
            new SalesOrderDto(
                91L,
                UUID.randomUUID(),
                "SO-REPLAY-001",
                "DISPATCHED",
                new BigDecimal("236.00"),
                new BigDecimal("200.00"),
                new BigDecimal("36.00"),
                new BigDecimal("18.00"),
                "ORDER_TOTAL",
                false,
                BigDecimal.ZERO,
                "INR",
                "Mock Dealer",
                "CREDIT",
                "trace-1",
                Instant.now(),
                List.of(),
                List.of()));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "seedReadyToConfirmOrder",
        salesOrderCrudService,
        salesFulfillmentService,
        dealer);

    verify(salesFulfillmentService, never()).reserveForOrder(91L);
  }

  @Test
  void seedFinishedGood_usesExplicitCatalogMetadataForCreatedRecords() {
    Company company = company("MOCK");
    ProductionBrand brand = brand(company);
    Account wipAccount = account(1180L, "WIP_PACK");
    Map<String, Account> accounts = fixtureAccounts();

    when(finishedGoodRepository.findByCompanyAndProductCode(
            company, E2eFixtureCatalog.ORDER_PRIMARY_SKU))
        .thenReturn(Optional.empty());
    when(finishedGoodRepository.save(any(FinishedGood.class)))
        .thenAnswer(
            invocation -> {
              FinishedGood finishedGood = invocation.getArgument(0, FinishedGood.class);
              if (finishedGood.getId() == null) {
                ReflectionTestUtils.setField(finishedGood, "id", 501L);
              }
              if (finishedGood.getCurrentStock() == null) {
                finishedGood.setCurrentStock(BigDecimal.ZERO);
              }
              return finishedGood;
            });
    when(productRepository.findByCompanyAndSkuCode(company, E2eFixtureCatalog.ORDER_PRIMARY_SKU))
        .thenReturn(Optional.empty());
    when(productRepository.save(any(ProductionProduct.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, ProductionProduct.class));

    FinishedGood seeded =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            initializer,
            "seedFinishedGood",
            company,
            finishedGoodRepository,
            productRepository,
            accounts,
            brand,
            E2eFixtureCatalog.ORDER_PRIMARY_SKU,
            E2eFixtureCatalog.ORDER_PRIMARY_NAME,
            E2eFixtureCatalog.ORDER_PRIMARY_BASE_PRICE,
            E2eFixtureCatalog.ORDER_PRIMARY_GST_RATE,
            "FIFO",
            wipAccount);

    assertThat(seeded.getProductCode()).isEqualTo(E2eFixtureCatalog.ORDER_PRIMARY_SKU);
    assertThat(seeded.getName()).isEqualTo(E2eFixtureCatalog.ORDER_PRIMARY_NAME);

    ArgumentCaptor<ProductionProduct> productCaptor =
        ArgumentCaptor.forClass(ProductionProduct.class);
    verify(productRepository).save(productCaptor.capture());
    ProductionProduct product = productCaptor.getValue();
    assertThat(product.getProductName()).isEqualTo(E2eFixtureCatalog.ORDER_PRIMARY_NAME);
    assertThat(product.getBasePrice())
        .isEqualByComparingTo(E2eFixtureCatalog.ORDER_PRIMARY_BASE_PRICE);
    assertThat(product.getGstRate()).isEqualByComparingTo(E2eFixtureCatalog.ORDER_PRIMARY_GST_RATE);
    assertThat(product.getMetadata())
        .containsEntry("wipAccountId", wipAccount.getId())
        .containsEntry("semiFinishedAccountId", accounts.get("INV").getId())
        .containsEntry("fgValuationAccountId", accounts.get("INV").getId())
        .containsEntry("fgCogsAccountId", accounts.get("COGS").getId())
        .containsEntry("fgRevenueAccountId", accounts.get("REV").getId())
        .containsEntry("fgDiscountAccountId", accounts.get("DISC").getId())
        .containsEntry("fgTaxAccountId", accounts.get("GST_OUT").getId())
        .containsEntry("laborAppliedAccountId", accounts.get("LABOR").getId())
        .containsEntry("overheadAppliedAccountId", accounts.get("OVERHEAD").getId());
  }

  @Test
  void seedBatches_createsDedicatedE2ePrimaryBatchAndUpdatesStock() {
    Company company = company("MOCK");
    FinishedGood fgFifo = finishedGood(company, 11L, "FG-GST");
    FinishedGood fgLifo = finishedGood(company, 12L, "FG-LIFO");
    FinishedGood fgKit = finishedGood(company, 13L, "FG-KIT");
    FinishedGood fgE2ePrimary = finishedGood(company, 14L, E2eFixtureCatalog.ORDER_PRIMARY_SKU);

    Map<Long, FinishedGood> finishedGoodsById =
        Map.of(
            11L, fgFifo,
            12L, fgLifo,
            13L, fgKit,
            14L, fgE2ePrimary);
    when(batchRepository.existsByFinishedGoodAndBatchCodeIgnoreCase(
            any(FinishedGood.class), anyString()))
        .thenReturn(false);
    when(batchRepository.save(any(FinishedGoodBatch.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, FinishedGoodBatch.class));
    when(finishedGoodRepository.findById(any(Long.class)))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(finishedGoodsById.get(invocation.getArgument(0, Long.class))));
    when(finishedGoodRepository.save(any(FinishedGood.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, FinishedGood.class));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "seedBatches",
        company,
        batchRepository,
        finishedGoodRepository,
        fgFifo,
        fgLifo,
        fgKit,
        fgE2ePrimary);

    ArgumentCaptor<FinishedGoodBatch> batchCaptor =
        ArgumentCaptor.forClass(FinishedGoodBatch.class);
    verify(batchRepository, org.mockito.Mockito.times(6)).save(batchCaptor.capture());
    assertThat(batchCaptor.getAllValues())
        .anySatisfy(
            batch -> {
              assertThat(batch.getFinishedGood()).isEqualTo(fgE2ePrimary);
              assertThat(batch.getBatchCode())
                  .isEqualTo(E2eFixtureCatalog.ORDER_PRIMARY_BATCH_CODE);
              assertThat(batch.getQuantityTotal())
                  .isEqualByComparingTo(E2eFixtureCatalog.ORDER_PRIMARY_STOCK_QUANTITY);
              assertThat(batch.getUnitCost())
                  .isEqualByComparingTo(E2eFixtureCatalog.ORDER_PRIMARY_UNIT_COST);
            });
    assertThat(fgE2ePrimary.getCurrentStock())
        .isEqualByComparingTo(E2eFixtureCatalog.ORDER_PRIMARY_STOCK_QUANTITY);
  }

  @Test
  void createBatch_skipsWhenMatchingBatchAlreadyExists() {
    FinishedGood finishedGood = finishedGood(company("MOCK"), 55L, "FG-EXISTING");
    finishedGood.setCurrentStock(new BigDecimal("5"));
    when(batchRepository.existsByFinishedGoodAndBatchCodeIgnoreCase(
            finishedGood, E2eFixtureCatalog.ORDER_PRIMARY_BATCH_CODE))
        .thenReturn(true);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "createBatch",
        batchRepository,
        finishedGoodRepository,
        finishedGood,
        E2eFixtureCatalog.ORDER_PRIMARY_BATCH_CODE,
        E2eFixtureCatalog.ORDER_PRIMARY_STOCK_QUANTITY,
        E2eFixtureCatalog.ORDER_PRIMARY_UNIT_COST,
        Instant.parse("2026-02-01T00:00:00Z"));

    verify(batchRepository, never()).save(any(FinishedGoodBatch.class));
    verify(finishedGoodRepository, never()).save(any(FinishedGood.class));
    assertThat(finishedGood.getCurrentStock()).isEqualByComparingTo(new BigDecimal("5"));
  }

  private Company company(String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Co");
    company.setTimezone("UTC");
    return company;
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    role.setDescription(name);
    return role;
  }

  private ProductionBrand brand(Company company) {
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setCode("MOCK-BRAND");
    brand.setName("Mock Brand");
    return brand;
  }

  private Map<String, Account> fixtureAccounts() {
    Map<String, Account> accounts = new HashMap<>();
    accounts.put("INV", account(11L, "INV"));
    accounts.put("COGS", account(12L, "COGS"));
    accounts.put("REV", account(13L, "REV"));
    accounts.put("DISC", account(14L, "DISC"));
    accounts.put("GST_OUT", account(15L, "GST_OUT"));
    accounts.put("LABOR", account(16L, "LABOR"));
    accounts.put("OVERHEAD", account(17L, "OVERHEAD"));
    return accounts;
  }

  private Account account(Long id, String code) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCode(code);
    return account;
  }

  private FinishedGood finishedGood(Company company, Long id, String code) {
    FinishedGood finishedGood = new FinishedGood();
    ReflectionTestUtils.setField(finishedGood, "id", id);
    finishedGood.setCompany(company);
    finishedGood.setProductCode(code);
    finishedGood.setName(code);
    finishedGood.setCurrentStock(BigDecimal.ZERO);
    return finishedGood;
  }
}
