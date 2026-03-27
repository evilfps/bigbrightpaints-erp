package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.CoATemplate;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingRequest;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingResponse;

@ExtendWith(MockitoExtension.class)
class TenantOnboardingServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private CoATemplateService coATemplateService;
  @Mock private SystemSettingsRepository systemSettingsRepository;
  @Mock private TenantAdminProvisioningService tenantAdminProvisioningService;

  @Test
  void initializeDefaultSystemSettings_doesNotPersistCorsOrigin() {
    TenantOnboardingService service = newService();
    when(systemSettingsRepository.existsById(anyString())).thenReturn(false);

    Boolean changed = ReflectionTestUtils.invokeMethod(service, "initializeDefaultSystemSettings");

    assertThat(changed).isTrue();
    verify(systemSettingsRepository, times(2)).save(any());
    verify(systemSettingsRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
        setting -> setting != null && "cors.allowed-origins".equals(setting.getKey())));
  }

  @Test
  void onboardTenant_returnsCanonicalResponseWithoutTemporaryPasswordFields() {
    TenantOnboardingService service = newService();
    TenantOnboardingRequest request =
        new TenantOnboardingRequest(
            "Mock Company",
            "mock",
            "UTC",
            BigDecimal.valueOf(18),
            10L,
            1000L,
            1024L,
            5L,
            true,
            true,
            " admin@mock.com ",
            "Mock Admin",
            "GENERIC");

    CoATemplate template = new CoATemplate();
    template.setCode("GENERIC");
    template.setActive(true);
    when(coATemplateService.requireActiveTemplate("GENERIC")).thenReturn(template);
    when(companyRepository.findByCodeIgnoreCase("MOCK")).thenReturn(java.util.Optional.empty());
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("admin@mock.com", "MOCK"))
        .thenReturn(false);
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(
            invocation -> {
              Company company = invocation.getArgument(0);
              if (company.getId() == null) {
                ReflectionTestUtils.setField(company, "id", 99L);
              }
              return company;
            });
    AtomicLong accountIds = new AtomicLong(1L);
    when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account account = invocation.getArgument(0);
              if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", accountIds.getAndIncrement());
              }
              return account;
            });
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 77L);
    when(accountingPeriodService.ensurePeriod(any(Company.class), any())).thenReturn(period);
    when(systemSettingsRepository.existsById(anyString())).thenReturn(false);
    when(tenantAdminProvisioningService.provisionInitialAdmin(any(Company.class), anyString(), anyString()))
        .thenReturn("admin@mock.com");

    TenantOnboardingResponse response = service.onboardTenant(request);

    assertThat(response.companyId()).isEqualTo(99L);
    assertThat(response.companyCode()).isEqualTo("MOCK");
    assertThat(response.templateCode()).isEqualTo("GENERIC");
    assertThat(response.adminEmail()).isEqualTo("admin@mock.com");
    assertThat(response.tenantAdminProvisioned()).isTrue();
    assertThat(response.systemSettingsInitialized()).isTrue();
    verify(tenantAdminProvisioningService)
        .provisionInitialAdmin(any(Company.class), org.mockito.ArgumentMatchers.eq("admin@mock.com"), org.mockito.ArgumentMatchers.eq("Mock Admin"));
  }

  @Test
  void onboardTenant_seedsScopedCogsAndOpeningBalanceAccounts() {
    TenantOnboardingService service = newService();
    TenantOnboardingRequest request =
        new TenantOnboardingRequest(
            "Mock Company",
            "mock",
            "UTC",
            BigDecimal.valueOf(18),
            10L,
            1000L,
            1024L,
            5L,
            true,
            true,
            " admin@mock.com ",
            "Mock Admin",
            "GENERIC");

    CoATemplate template = new CoATemplate();
    template.setCode("GENERIC");
    template.setActive(true);
    when(coATemplateService.requireActiveTemplate("GENERIC")).thenReturn(template);
    when(companyRepository.findByCodeIgnoreCase("MOCK")).thenReturn(java.util.Optional.empty());
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("admin@mock.com", "MOCK"))
        .thenReturn(false);
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(
            invocation -> {
              Company company = invocation.getArgument(0);
              if (company.getId() == null) {
                ReflectionTestUtils.setField(company, "id", 99L);
              }
              return company;
            });
    List<Account> createdAccounts = new ArrayList<>();
    AtomicLong accountIds = new AtomicLong(1L);
    when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account account = invocation.getArgument(0);
              if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", accountIds.getAndIncrement());
              }
              createdAccounts.add(account);
              return account;
            });
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 77L);
    when(accountingPeriodService.ensurePeriod(any(Company.class), any())).thenReturn(period);
    when(systemSettingsRepository.existsById(anyString())).thenReturn(false);
    when(tenantAdminProvisioningService.provisionInitialAdmin(any(Company.class), anyString(), anyString()))
        .thenReturn("admin@mock.com");

    service.onboardTenant(request);

    Account cogs =
        createdAccounts.stream().filter(account -> "COGS".equals(account.getCode())).findFirst().orElseThrow();
    Account openingBalance =
        createdAccounts.stream()
            .filter(account -> "OPEN-BAL".equals(account.getCode()))
            .findFirst()
            .orElseThrow();
    assertThat(cogs.getType()).isEqualTo(AccountType.COGS);
    assertThat(openingBalance.getType()).isEqualTo(AccountType.EQUITY);
  }

  private TenantOnboardingService newService() {
    return new TenantOnboardingService(
        companyRepository,
        userAccountRepository,
        accountRepository,
        accountingPeriodService,
        coATemplateService,
        systemSettingsRepository,
        tenantAdminProvisioningService);
  }
}
