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
class TS_RuntimePortalBoundaryDelegatedCoverageTest {

    @Test
    void delegatedPortalBoundarySuites_pass_in_truth_lane() {
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.auth.CompanyContextFilterControlPlaneBindingTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.auth.SuperAdminTenantWorkflowIsolationIT");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.rbac.config.RbacSynchronizationConfigTest");
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
