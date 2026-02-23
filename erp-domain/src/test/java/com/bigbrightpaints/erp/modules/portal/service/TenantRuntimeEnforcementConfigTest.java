package com.bigbrightpaints.erp.modules.portal.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementConfigTest {

    @Mock
    private TenantRuntimeEnforcementInterceptor tenantRuntimeEnforcementInterceptor;
    @Mock
    private InterceptorRegistry registry;
    @Mock
    private InterceptorRegistration registration;

    @Test
    void addInterceptors_registersTenantRuntimeInterceptorForApiV1Paths() {
        when(registry.addInterceptor(tenantRuntimeEnforcementInterceptor)).thenReturn(registration);
        TenantRuntimeEnforcementConfig config = new TenantRuntimeEnforcementConfig(tenantRuntimeEnforcementInterceptor);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(tenantRuntimeEnforcementInterceptor);
        verify(registration).addPathPatterns("/api/v1/**");
    }
}
