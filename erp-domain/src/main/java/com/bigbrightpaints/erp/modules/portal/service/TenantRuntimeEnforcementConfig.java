package com.bigbrightpaints.erp.modules.portal.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantRuntimeEnforcementConfig implements WebMvcConfigurer {

    private final TenantRuntimeEnforcementInterceptor tenantRuntimeEnforcementInterceptor;

    public TenantRuntimeEnforcementConfig(TenantRuntimeEnforcementInterceptor tenantRuntimeEnforcementInterceptor) {
        this.tenantRuntimeEnforcementInterceptor = tenantRuntimeEnforcementInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Runtime enforcement is centralized in CompanyContextFilter via TenantRuntimeEnforcementService.
        // Keeping portal-level interceptor unregistered avoids duplicate checks and counter skew.
    }
}
