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
import java.util.List;
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

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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

    ReflectionTestUtils.invokeMethod(
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

    ReflectionTestUtils.invokeMethod(
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

    ReflectionTestUtils.invokeMethod(
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

    Company company = ReflectionTestUtils.invokeMethod(initializer, "createCompany", companyRepository);

    assertThat(company.getCode()).isEqualTo("MOCK");
    assertThat(company.getStateCode()).isEqualTo("MH");
  }

  @Test
  void createCompany_backfillsMissingStateCodeForExistingCompany() {
    Company existingCompany = company("MOCK");
    when(companyRepository.findByCodeIgnoreCase("MOCK")).thenReturn(Optional.of(existingCompany));
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));

    Company company = ReflectionTestUtils.invokeMethod(initializer, "createCompany", companyRepository);

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

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedReadyToConfirmOrder",
        salesOrderCrudService,
        salesFulfillmentService,
        dealer);

    verify(salesFulfillmentService, never()).reserveForOrder(91L);
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
}
