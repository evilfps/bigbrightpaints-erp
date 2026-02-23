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
        registry.addInterceptor(tenantRuntimeEnforcementInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}
