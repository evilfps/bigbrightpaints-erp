package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class TS_RuntimeTenantControlPlaneEnforcementTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private AuditService auditService;

    private final Map<String, Company> companiesByCode = new HashMap<>();
    private final Map<String, String> persistedSettingsByKey = new HashMap<>();

    private TenantRuntimeEnforcementService service;

    @BeforeEach
    void setUp() {
        Company acme = new Company();
        ReflectionTestUtils.setField(acme, "id", 1L);
        acme.setCode("ACME");
        companiesByCode.put("ACME", acme);

        service = new TenantRuntimeEnforcementService(
                companyRepository,
                systemSettingsRepository,
                userAccountRepository,
                auditService,
                5,
                10,
                20,
                15);

        lenient().when(companyRepository.findByCodeIgnoreCase(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(companiesByCode.get(code.trim().toUpperCase(Locale.ROOT)));
        });
        lenient().when(systemSettingsRepository.findById(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            String value = persistedSettingsByKey.get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new SystemSetting(key, value));
        });
        lenient().when(systemSettingsRepository.save(any(SystemSetting.class))).thenAnswer(invocation -> {
            SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
            persistedSettingsByKey.put(setting.getKey(), setting.getValue());
            return setting;
        });
        lenient().when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(anyLong()))
                .thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void controlPlaneMutation_deniesUnauthenticatedRequests_failClosed() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.holdTenant("ACME", "maintenance", "ops@bbp.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authenticated SUPER_ADMIN authority required");

        verifyNoInteractions(companyRepository, systemSettingsRepository, auditService, userAccountRepository);
    }

    @Test
    void controlPlaneMutation_deniesNonSuperAdminRequests_failClosed() {
        authenticateAs("ROLE_ADMIN");

        assertThatThrownBy(() -> service.holdTenant("ACME", "maintenance", "ops@bbp.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required");

        verifyNoInteractions(companyRepository, systemSettingsRepository, auditService, userAccountRepository);
    }

    @Test
    void controlPlaneMutation_allowsSuperAdmin_andPersistsCompanyPolicy() {
        authenticateAs("ROLE_SUPER_ADMIN");

        TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "maintenance_window",
                7,
                11,
                13,
                "ops@bbp.com");

        assertThat(snapshot.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
        assertThat(snapshot.reasonCode()).isEqualTo("MAINTENANCE_WINDOW");
        assertThat(snapshot.maxConcurrentRequests()).isEqualTo(7);
        assertThat(snapshot.maxRequestsPerMinute()).isEqualTo(11);
        assertThat(persistedSettingsByKey.get(keyHoldState(1L))).isEqualTo("HOLD");
        assertThat(persistedSettingsByKey.get(keyHoldReason(1L))).isEqualTo("MAINTENANCE_WINDOW");
        assertThat(persistedSettingsByKey.get(keyMaxConcurrentRequests(1L))).isEqualTo("7");
        assertThat(persistedSettingsByKey.get(keyMaxRequestsPerMinute(1L))).isEqualTo("11");
        assertThat(persistedSettingsByKey.get(keyPolicyReference(1L))).isNotBlank();
        assertThat(persistedSettingsByKey.get(keyPolicyUpdatedAt(1L))).isNotBlank();
    }

    @Test
    void controlPlaneMutation_failsClosed_whenTargetCompanyMissing() {
        authenticateAs("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> service.holdTenant("UNKNOWN", "maintenance", "ops@bbp.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found: UNKNOWN");
    }

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "actor@bbp.com",
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority(authority))));
    }

    private String keyHoldState(long companyId) {
        return "tenant.runtime.hold-state." + companyId;
    }

    private String keyHoldReason(long companyId) {
        return "tenant.runtime.hold-reason." + companyId;
    }

    private String keyMaxRequestsPerMinute(long companyId) {
        return "tenant.runtime.max-requests-per-minute." + companyId;
    }

    private String keyMaxConcurrentRequests(long companyId) {
        return "tenant.runtime.max-concurrent-requests." + companyId;
    }

    private String keyPolicyReference(long companyId) {
        return "tenant.runtime.policy-reference." + companyId;
    }

    private String keyPolicyUpdatedAt(long companyId) {
        return "tenant.runtime.policy-updated-at." + companyId;
    }
}
