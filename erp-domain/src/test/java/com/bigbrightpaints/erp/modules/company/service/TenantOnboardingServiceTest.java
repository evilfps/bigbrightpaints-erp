package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantOnboardingServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RoleService roleService;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Mock
    private CoATemplateService coATemplateService;

    @Mock
    private EmailService emailService;

    @Mock
    private SystemSettingsRepository systemSettingsRepository;

    @Test
    void initializeDefaultSystemSettings_doesNotPersistCorsOrigin() {
        TenantOnboardingService service = new TenantOnboardingService(
                companyRepository,
                userAccountRepository,
                roleService,
                roleRepository,
                passwordEncoder,
                accountRepository,
                accountingPeriodService,
                coATemplateService,
                emailService,
                systemSettingsRepository);
        when(systemSettingsRepository.existsById(anyString())).thenReturn(false);

        Boolean changed = ReflectionTestUtils.invokeMethod(service, "initializeDefaultSystemSettings");

        assertThat(changed).isTrue();
        verify(systemSettingsRepository).save(argThat(setting ->
                setting != null
                        && "auto-approval.enabled".equals(setting.getKey())
                        && "true".equals(setting.getValue())));
        verify(systemSettingsRepository).save(argThat(setting ->
                setting != null
                        && "period-lock.enforced".equals(setting.getKey())
                        && "true".equals(setting.getValue())));
        verify(systemSettingsRepository, times(2)).save(argThat(setting -> setting != null));
        verify(systemSettingsRepository, never()).save(argThat(setting ->
                setting != null && "cors.allowed-origins".equals(setting.getKey())));
    }

    @Test
    void requireAdminRole_synchronizesBeforeLoadingPersistedRole() {
        TenantOnboardingService service = new TenantOnboardingService(
                companyRepository,
                userAccountRepository,
                roleService,
                roleRepository,
                passwordEncoder,
                accountRepository,
                accountingPeriodService,
                coATemplateService,
                emailService,
                systemSettingsRepository);
        Role persistedRole = new Role();
        persistedRole.setName("ROLE_ADMIN");
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(persistedRole));

        Role resolved = ReflectionTestUtils.invokeMethod(service, "requireAdminRole");

        assertThat(resolved).isSameAs(persistedRole);
        verify(roleService).ensureRoleExists("ROLE_ADMIN");
        verify(roleRepository).findByName("ROLE_ADMIN");
    }

    @Test
    void requireAdminRole_failsFastWhenAdminRoleMissingAfterSynchronization() {
        TenantOnboardingService service = new TenantOnboardingService(
                companyRepository,
                userAccountRepository,
                roleService,
                roleRepository,
                passwordEncoder,
                accountRepository,
                accountingPeriodService,
                coATemplateService,
                emailService,
                systemSettingsRepository);
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "requireAdminRole"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("ROLE_ADMIN must exist before tenant onboarding");
        verify(roleService).ensureRoleExists("ROLE_ADMIN");
        verify(roleRepository).findByName("ROLE_ADMIN");
    }

    @Test
    void genericTemplateBlueprints_includesOpeningBalanceEquityAccount() {
        TenantOnboardingService service = new TenantOnboardingService(
                companyRepository,
                userAccountRepository,
                roleService,
                roleRepository,
                passwordEncoder,
                accountRepository,
                accountingPeriodService,
                coATemplateService,
                emailService,
                systemSettingsRepository);

        @SuppressWarnings("unchecked")
        List<Object> blueprints = (List<Object>) ReflectionTestUtils.invokeMethod(service, "genericTemplateBlueprints");

        Object openBal = blueprints.stream()
                .filter(blueprint -> "OPEN-BAL".equals(ReflectionTestUtils.invokeMethod(blueprint, "code")))
                .findFirst()
                .orElseThrow();

        String name = ReflectionTestUtils.invokeMethod(openBal, "name");
        AccountType type = ReflectionTestUtils.invokeMethod(openBal, "type");
        String parentCode = ReflectionTestUtils.invokeMethod(openBal, "parentCode");

        assertThat(name).isEqualTo("Opening Balance");
        assertThat(type).isEqualTo(AccountType.EQUITY);
        assertThat(parentCode).isEqualTo("3000");
    }
}
