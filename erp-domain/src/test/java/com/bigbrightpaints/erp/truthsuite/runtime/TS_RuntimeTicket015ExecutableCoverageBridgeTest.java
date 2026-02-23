package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

@Tag("critical")
class TS_RuntimeTicket015ExecutableCoverageBridgeTest {

    @Test
    void delegatedTicket015CoverageSuites_pass_in_truth_lane() {
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.core.config.TS_RuntimeSmtpPropertiesValidatorExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.core.security.TS_RuntimeCompanyContextFilterExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.accounting.service.TS_RuntimeAccountingFacadeExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.company.controller.TS_RuntimeCompanyControllerExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.company.service.TS_RuntimeTenantRuntimeEnforcementServiceExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.portal.service.TS_RuntimeTenantRuntimeEnforcementInterceptorExecutableCoverageTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.rbac.domain.TS_RuntimeSystemRoleExecutableCoverageTest");
    }

    private void assertDelegatedSuitePasses(String className) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(className))
                .build();
        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summaryListener);
        launcher.execute(request);
        TestExecutionSummary summary = summaryListener.getSummary();
        assertThat(summary.getTestsFoundCount()).isGreaterThan(0L);
        assertThat(summary.getTestsSucceededCount()).isGreaterThan(0L);
        assertThat(summary.getTestsAbortedCount()).isZero();
        assertThat(summary.getTestsFailedCount()).isZero();
    }
}
