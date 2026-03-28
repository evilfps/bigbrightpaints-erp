package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;

@ExtendWith(MockitoExtension.class)
class BenchmarkDataInitializerTest {

  @Mock private RoleRepository roleRepository;
  @Mock private UserAccountRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private ProductionProductRepository productRepository;

  private BenchmarkDataInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new BenchmarkDataInitializer();
    lenient()
        .when(roleRepository.findByName(anyString()))
        .thenAnswer(invocation -> Optional.of(role(invocation.getArgument(0, String.class))));
    lenient()
        .when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, UserAccount.class));
  }

  @Test
  void seedRolesAndUsers_createsScopedBenchmarkAdminWhenMissing() {
    Company company = company("BBP");
    when(passwordEncoder.encode("Temp123!")).thenReturn("encoded-password");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "benchmark@example.com", "BBP"))
        .thenReturn(Optional.empty());

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " Benchmark@Example.com ",
        " Temp123! ");

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    UserAccount saved = userCaptor.getValue();

    assertThat(saved.getEmail()).isEqualTo("benchmark@example.com");
    assertThat(saved.getAuthScopeCode()).isEqualTo("BBP");
    assertThat(saved.getCompany()).isEqualTo(company);
    assertThat(saved.isMustChangePassword()).isTrue();
    assertThat(saved.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY");
  }

  @Test
  void seedRolesAndUsers_updatesExistingScopedBenchmarkAdminWhenPresent() {
    Company company = company("BBP");
    UserAccount existingAdmin =
        new UserAccount("legacy@example.com", "OLD", "hash", "Legacy Admin");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "legacy@example.com", "BBP"))
        .thenReturn(Optional.of(existingAdmin));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " legacy@example.com ",
        "");

    verify(userRepository).save(existingAdmin);
    assertThat(existingAdmin.getAuthScopeCode()).isEqualTo("BBP");
    assertThat(existingAdmin.getCompany()).isEqualTo(company);
    assertThat(existingAdmin.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY");
  }

  @Test
  void seedFinishedGoods_seedsSemiFinishedProductAndPackedFgWithoutBulkSuffix() {
    Company company = company("BBP");
    ProductionBrand brand = brand(company);
    Map<String, Account> accounts = accounts();

    when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-PEE-5L"))
        .thenReturn(Optional.empty());
    when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-PEE-10L"))
        .thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndSkuCode(company, "SF-PEE")).thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndSkuCode(company, "FG-PEE-5L"))
        .thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndSkuCode(company, "FG-PEE-10L"))
        .thenReturn(Optional.empty());
    when(finishedGoodRepository.save(any(FinishedGood.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, FinishedGood.class));
    when(productRepository.save(any(ProductionProduct.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, ProductionProduct.class));

    ReflectionTestUtils.invokeMethod(
        initializer, "seedFinishedGoods", company, finishedGoodRepository, productRepository, accounts, brand);

    ArgumentCaptor<FinishedGood> fgCaptor = ArgumentCaptor.forClass(FinishedGood.class);
    verify(finishedGoodRepository, times(2)).save(fgCaptor.capture());
    assertThat(fgCaptor.getAllValues())
        .extracting(FinishedGood::getProductCode)
        .containsExactlyInAnyOrder("FG-PEE-5L", "FG-PEE-10L")
        .allSatisfy(code -> assertThat(code).doesNotEndWith("-BULK"));

    ArgumentCaptor<ProductionProduct> productCaptor = ArgumentCaptor.forClass(ProductionProduct.class);
    verify(productRepository, times(3)).save(productCaptor.capture());

    List<ProductionProduct> savedProducts = productCaptor.getAllValues();
    assertThat(savedProducts)
        .extracting(ProductionProduct::getSkuCode)
        .containsExactlyInAnyOrder("SF-PEE", "FG-PEE-5L", "FG-PEE-10L");
    assertThat(savedProducts)
        .filteredOn(p -> "SF-PEE".equals(p.getSkuCode()))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.getCategory()).isEqualTo("SEMI_FINISHED");
              assertThat(p.getMetadata()).containsEntry("wipAccountId", 1300L);
              assertThat(p.getMetadata()).containsEntry("semiFinishedAccountId", 1400L);
            });
    assertThat(savedProducts)
        .filteredOn(p -> "FG-PEE-5L".equals(p.getSkuCode()) || "FG-PEE-10L".equals(p.getSkuCode()))
        .allSatisfy(
            p -> {
              assertThat(p.getCategory()).isEqualTo("FINISHED_GOOD");
              assertThat(p.getMetadata()).containsEntry("fgValuationAccountId", 1500L);
              assertThat(p.getMetadata()).containsEntry("wipAccountId", 1310L);
              assertThat(p.getMetadata()).containsEntry("fgCogsAccountId", 5000L);
            });
  }

  private Company company(String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Co");
    company.setTimezone("UTC");
    return company;
  }

  private ProductionBrand brand(Company company) {
    ProductionBrand brand = new ProductionBrand();
    ReflectionTestUtils.setField(brand, "id", 11L);
    brand.setCode("BBP");
    brand.setName("BigBright");
    brand.setCompany(company);
    return brand;
  }

  private Map<String, Account> accounts() {
    return Map.ofEntries(
        Map.entry("SF_INV", account(1400L)),
        Map.entry("WIP_MIX", account(1300L)),
        Map.entry("FG_INV", account(1500L)),
        Map.entry("WIP_PACK", account(1310L)),
        Map.entry("COGS", account(5000L)),
        Map.entry("REV", account(4000L)),
        Map.entry("DISC", account(4100L)),
        Map.entry("GST_OUT", account(2100L)),
        Map.entry("WASTAGE", account(5300L)),
        Map.entry("LABOR", account(5100L)),
        Map.entry("OVERHEAD", account(5200L)));
  }

  private Account account(Long id) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    return account;
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    role.setDescription(name);
    return role;
  }
}
