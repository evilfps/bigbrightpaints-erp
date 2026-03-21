package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class ModuleGatingInterceptorTest {

    @Mock
    private ModuleGatingService moduleGatingService;

    private ModuleGatingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ModuleGatingInterceptor(moduleGatingService);
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void preHandle_allowsRequestWhenModuleIsEnabled() {
        CompanyContextHolder.setCompanyCode("ACME");
        when(moduleGatingService.isEnabledForCurrentCompany(CompanyModule.REPORTS_ADVANCED)).thenReturn(true);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance"),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        verify(moduleGatingService).isEnabledForCurrentCompany(CompanyModule.REPORTS_ADVANCED);
    }

    @Test
    void preHandle_throwsForbiddenWhenModuleIsDisabled() {
        CompanyContextHolder.setCompanyCode("ACME");
        when(moduleGatingService.isEnabledForCurrentCompany(CompanyModule.MANUFACTURING)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/factory/batches"),
                new MockHttpServletResponse(),
                new Object()))
                .isInstanceOf(ApplicationException.class)
                .extracting(error -> ((ApplicationException) error).getErrorCode())
                .isEqualTo(ErrorCode.MODULE_DISABLED);
    }

    @Test
    void preHandle_skipsGatingWhenCompanyContextMissing() {
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance"),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        verify(moduleGatingService, never()).isEnabledForCurrentCompany(CompanyModule.REPORTS_ADVANCED);
    }

    @Test
    void resolveTargetModule_routesCanonicalAccountingReportsThroughReportsAdvanced() {
        assertThat(interceptor.resolveTargetModule("/api/v1/reports/aging/dealer/77"))
                .isEqualTo(CompanyModule.REPORTS_ADVANCED);
        assertThat(interceptor.resolveTargetModule("/api/v1/reports/balance-sheet/hierarchy"))
                .isEqualTo(CompanyModule.REPORTS_ADVANCED);
    }

}
