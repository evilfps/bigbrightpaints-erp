package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModuleGatingServiceTest {

    @Mock
    private CompanyContextService companyContextService;

    @Test
    void isEnabledForCurrentCompany_coreModulesAreAlwaysEnabled() {
        ModuleGatingService service = new ModuleGatingService(companyContextService);

        boolean enabled = service.isEnabledForCurrentCompany(CompanyModule.ACCOUNTING);

        assertThat(enabled).isTrue();
        verify(companyContextService, never()).requireCurrentCompany();
    }

    @Test
    void isEnabledForCurrentCompany_returnsFalseWhenGatableModuleNotEnabled() {
        Company company = new Company();
        company.setEnabledModules(Set.of(CompanyModule.PORTAL.name()));
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        ModuleGatingService service = new ModuleGatingService(companyContextService);

        boolean enabled = service.isEnabledForCurrentCompany(CompanyModule.MANUFACTURING);

        assertThat(enabled).isFalse();
    }

    @Test
    void normalizeRequestedEnabledModules_dropsCoreModulesFromRequestedList() {
        ModuleGatingService service = new ModuleGatingService(companyContextService);

        Set<String> normalized = service.normalizeRequestedEnabledModules(Set.of(
                "MANUFACTURING",
                "ACCOUNTING",
                "PORTAL",
                "unknown"));

        assertThat(normalized).containsExactlyInAnyOrder("MANUFACTURING", "PORTAL");
        assertThat(normalized).doesNotContain("ACCOUNTING");
    }

    @Test
    void resolveEnabledGatableModules_defaultsExcludeHrPayroll() {
        ModuleGatingService service = new ModuleGatingService(companyContextService);

        Set<String> enabled = service.resolveEnabledGatableModules(null);

        assertThat(enabled)
                .containsExactlyInAnyOrder("MANUFACTURING", "PURCHASING", "PORTAL", "REPORTS_ADVANCED")
                .doesNotContain("HR_PAYROLL");
    }
}
