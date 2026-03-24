package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance"),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        verify(moduleGatingService)
                .requireEnabledForCurrentCompany(CompanyModule.REPORTS_ADVANCED, "/api/v1/reports/trial-balance");
    }

    @Test
    void preHandle_throwsForbiddenWhenModuleIsDisabled() {
        CompanyContextHolder.setCompanyCode("ACME");
        doThrow(new ApplicationException(ErrorCode.MODULE_DISABLED, "disabled"))
                .when(moduleGatingService)
                .requireEnabledForCurrentCompany(CompanyModule.MANUFACTURING, "/api/v1/factory/batches");

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/factory/batches"),
                new MockHttpServletResponse(),
                new Object()))
                .isInstanceOf(ApplicationException.class)
                .extracting(error -> ((ApplicationException) error).getErrorCode())
                .isEqualTo(ErrorCode.MODULE_DISABLED);
    }

    @Test
    void preHandle_throwsForbiddenForAccountingPayrollRouteWhenModuleIsDisabled() {
        CompanyContextHolder.setCompanyCode("ACME");
        doThrow(new ApplicationException(ErrorCode.MODULE_DISABLED, "disabled"))
                .when(moduleGatingService)
                .requireEnabledForCurrentCompany(CompanyModule.HR_PAYROLL, "/api/v1/accounting/payroll/payments/batch");

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/v1/accounting/payroll/payments/batch"),
                new MockHttpServletResponse(),
                new Object()))
                .isInstanceOf(ApplicationException.class)
                .extracting(error -> ((ApplicationException) error).getErrorCode())
                .isEqualTo(ErrorCode.MODULE_DISABLED);
    }

    @Test
    void resolveTargetModule_prefersHrPayrollForAccountingPayrollRoutes() {
        assertThat(interceptor.resolveTargetModule("/api/v1/accounting/payroll/payments"))
                .isEqualTo(CompanyModule.HR_PAYROLL);
    }

    @Test
    void preHandle_skipsGatingWhenCompanyContextMissing() {
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance"),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        verify(moduleGatingService, never())
                .requireEnabledForCurrentCompany(CompanyModule.REPORTS_ADVANCED, "/api/v1/reports/trial-balance");
    }

    @Test
    void resolveTargetModule_routesCanonicalAccountingReportsThroughReportsAdvanced() {
        assertThat(interceptor.resolveTargetModule("/api/v1/reports/aging/dealer/77"))
                .isEqualTo(CompanyModule.REPORTS_ADVANCED);
        assertThat(interceptor.resolveTargetModule("/api/v1/reports/balance-sheet/hierarchy"))
                .isEqualTo(CompanyModule.REPORTS_ADVANCED);
    }

}
