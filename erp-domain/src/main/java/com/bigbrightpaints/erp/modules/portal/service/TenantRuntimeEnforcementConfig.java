package com.bigbrightpaints.erp.modules.portal.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.bigbrightpaints.erp.modules.company.service.ModuleGatingInterceptor;
import com.bigbrightpaints.erp.modules.company.service.TenantUsageMetricsInterceptor;

@Configuration
public class TenantRuntimeEnforcementConfig implements WebMvcConfigurer {

  private final TenantRuntimeEnforcementInterceptor tenantRuntimeEnforcementInterceptor;
  private final TenantUsageMetricsInterceptor tenantUsageMetricsInterceptor;
  private final ModuleGatingInterceptor moduleGatingInterceptor;
  private final DealerPortalFinanceReadOnlyInterceptor dealerPortalFinanceReadOnlyInterceptor;

  public TenantRuntimeEnforcementConfig(
      TenantRuntimeEnforcementInterceptor tenantRuntimeEnforcementInterceptor,
      TenantUsageMetricsInterceptor tenantUsageMetricsInterceptor,
      ModuleGatingInterceptor moduleGatingInterceptor,
      DealerPortalFinanceReadOnlyInterceptor dealerPortalFinanceReadOnlyInterceptor) {
    this.tenantRuntimeEnforcementInterceptor = tenantRuntimeEnforcementInterceptor;
    this.tenantUsageMetricsInterceptor = tenantUsageMetricsInterceptor;
    this.moduleGatingInterceptor = moduleGatingInterceptor;
    this.dealerPortalFinanceReadOnlyInterceptor = dealerPortalFinanceReadOnlyInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(tenantUsageMetricsInterceptor).addPathPatterns("/api/v1/**");
    registry.addInterceptor(moduleGatingInterceptor).addPathPatterns("/api/v1/**");
    registry
        .addInterceptor(dealerPortalFinanceReadOnlyInterceptor)
        .addPathPatterns("/api/v1/dealer-portal", "/api/v1/dealer-portal/**");
    registry.addInterceptor(tenantRuntimeEnforcementInterceptor).addPathPatterns("/api/v1/**");
  }
}
