package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ModuleGatingService {

    private final CompanyContextService companyContextService;

    public ModuleGatingService(CompanyContextService companyContextService) {
        this.companyContextService = companyContextService;
    }

    public boolean isEnabledForCurrentCompany(CompanyModule module) {
        if (module == null || module.isCore()) {
            return true;
        }
        Company company = companyContextService.requireCurrentCompany();
        return resolveEnabledGatableModules(company).contains(module.name());
    }

    public void requireEnabledForCurrentCompany(CompanyModule module, String path) {
        if (module == null || module.isCore() || isEnabledForCurrentCompany(module)) {
            return;
        }
        String companyCode = CompanyContextHolder.getCompanyCode();
        ApplicationException ex = new ApplicationException(
                ErrorCode.MODULE_DISABLED,
                "Module " + module.name() + " is disabled for the current tenant")
                .withDetail("module", module.name());
        if (StringUtils.hasText(companyCode)) {
            ex.withDetail("companyCode", companyCode);
        }
        if (StringUtils.hasText(path)) {
            ex.withDetail("path", path);
        }
        throw ex;
    }

    public Set<String> resolveEnabledGatableModules(Company company) {
        if (company == null) {
            return new LinkedHashSet<>(CompanyModule.defaultEnabledGatableModuleNames());
        }
        return CompanyModule.normalizeEnabledGatableModuleNames(company.getEnabledModules());
    }

    public Set<String> normalizeRequestedEnabledModules(Set<String> requestedModules) {
        return CompanyModule.normalizeEnabledGatableModuleNames(requestedModules);
    }
}
