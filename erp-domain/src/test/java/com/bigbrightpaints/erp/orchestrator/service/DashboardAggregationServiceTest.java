package com.bigbrightpaints.erp.orchestrator.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardAggregationServiceTest {

    @Mock
    private IntegrationCoordinator integrationCoordinator;

    @Test
    void dashboards_delegateToIntegrationCoordinator() {
        DashboardAggregationService service = new DashboardAggregationService(integrationCoordinator);

        when(integrationCoordinator.fetchAdminDashboard("COMP")).thenReturn(Map.of("a", 1));
        when(integrationCoordinator.fetchFactoryDashboard("COMP")).thenReturn(Map.of("b", 2));
        when(integrationCoordinator.fetchFinanceDashboard("COMP")).thenReturn(Map.of("c", 3));

        assertThat(service.adminDashboard("COMP")).containsEntry("a", 1);
        assertThat(service.factoryDashboard("COMP")).containsEntry("b", 2);
        assertThat(service.financeDashboard("COMP")).containsEntry("c", 3);

        verify(integrationCoordinator).fetchAdminDashboard("COMP");
        verify(integrationCoordinator).fetchFactoryDashboard("COMP");
        verify(integrationCoordinator).fetchFinanceDashboard("COMP");
    }
}

