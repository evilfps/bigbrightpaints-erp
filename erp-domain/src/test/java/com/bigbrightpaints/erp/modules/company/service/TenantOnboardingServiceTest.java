package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@ExtendWith(MockitoExtension.class)
class TenantOnboardingServiceTest {

  @Mock private CompanyRepository companyRepository;

  @Mock private UserAccountRepository userAccountRepository;

  @Mock private AccountRepository accountRepository;

  @Mock private AccountingPeriodService accountingPeriodService;

  @Mock private CoATemplateService coATemplateService;

  @Mock private TenantAdminProvisioningService tenantAdminProvisioningService;

  @Mock private SystemSettingsRepository systemSettingsRepository;

  @Test
  void initializeDefaultSystemSettings_doesNotPersistCorsOrigin() {
    TenantOnboardingService service =
        new TenantOnboardingService(
            companyRepository,
            userAccountRepository,
            accountRepository,
            accountingPeriodService,
            coATemplateService,
            tenantAdminProvisioningService,
            systemSettingsRepository);
    when(systemSettingsRepository.existsById(anyString())).thenReturn(false);

    Boolean changed = ReflectionTestUtils.invokeMethod(service, "initializeDefaultSystemSettings");

    assertThat(changed).isTrue();
    verify(systemSettingsRepository)
        .save(
            argThat(
                setting ->
                    setting != null
                        && "auto-approval.enabled".equals(setting.getKey())
                        && "true".equals(setting.getValue())));
    verify(systemSettingsRepository)
        .save(
            argThat(
                setting ->
                    setting != null
                        && "period-lock.enforced".equals(setting.getKey())
                        && "true".equals(setting.getValue())));
    verify(systemSettingsRepository, times(2)).save(argThat(setting -> setting != null));
    verify(systemSettingsRepository, never())
        .save(
            argThat(setting -> setting != null && "cors.allowed-origins".equals(setting.getKey())));
  }

  @Test
  void createTenantAdmin_failsClosedWhenCredentialEmailDeliveryDisabled() {
    TenantOnboardingService service =
        new TenantOnboardingService(
            companyRepository,
            userAccountRepository,
            accountRepository,
            accountingPeriodService,
            coATemplateService,
            tenantAdminProvisioningService,
            systemSettingsRepository);
    when(tenantAdminProvisioningService.isCredentialEmailDeliveryEnabled()).thenReturn(false);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    service, "createTenantAdmin", company("ACME"), "admin@acme.com", "Acme Admin"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Credential email delivery is disabled");
    verify(tenantAdminProvisioningService, never())
        .provisionInitialAdmin(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
  }

  @Test
  void resolveAdminDisplayName_prefersExplicitValue_thenCompanyName_thenFallback() {
    TenantOnboardingService service =
        new TenantOnboardingService(
            companyRepository,
            userAccountRepository,
            accountRepository,
            accountingPeriodService,
            coATemplateService,
            tenantAdminProvisioningService,
            systemSettingsRepository);

    String explicitDisplayName =
        ReflectionTestUtils.invokeMethod(
            service, "resolveAdminDisplayName", "  Named Admin  ", company("ACME"));
    String companyDefaultDisplayName =
        ReflectionTestUtils.invokeMethod(
            service, "resolveAdminDisplayName", "   ", company("ACME"));
    String fallbackDisplayName =
        ReflectionTestUtils.invokeMethod(service, "resolveAdminDisplayName", null, null);

    assertThat(explicitDisplayName).isEqualTo("Named Admin");
    assertThat(companyDefaultDisplayName).isEqualTo("Company ACME Admin");
    assertThat(fallbackDisplayName).isEqualTo("Company Admin");
  }

  @Test
  void genericTemplateBlueprints_includesOpeningBalanceEquityAccount() {
    TenantOnboardingService service =
        new TenantOnboardingService(
            companyRepository,
            userAccountRepository,
            accountRepository,
            accountingPeriodService,
            coATemplateService,
            tenantAdminProvisioningService,
            systemSettingsRepository);

    @SuppressWarnings("unchecked")
    List<Object> blueprints =
        (List<Object>) ReflectionTestUtils.invokeMethod(service, "genericTemplateBlueprints");

    Object openBal =
        blueprints.stream()
            .filter(
                blueprint -> "OPEN-BAL".equals(ReflectionTestUtils.invokeMethod(blueprint, "code")))
            .findFirst()
            .orElseThrow();

    String name = ReflectionTestUtils.invokeMethod(openBal, "name");
    AccountType type = ReflectionTestUtils.invokeMethod(openBal, "type");
    String parentCode = ReflectionTestUtils.invokeMethod(openBal, "parentCode");

    assertThat(name).isEqualTo("Opening Balance");
    assertThat(type).isEqualTo(AccountType.EQUITY);
    assertThat(parentCode).isEqualTo("3000");
  }

  private com.bigbrightpaints.erp.modules.company.domain.Company company(String code) {
    com.bigbrightpaints.erp.modules.company.domain.Company company =
        new com.bigbrightpaints.erp.modules.company.domain.Company();
    company.setCode(code);
    company.setName("Company " + code);
    ReflectionTestUtils.setField(company, "id", 10L);
    return company;
  }
}
