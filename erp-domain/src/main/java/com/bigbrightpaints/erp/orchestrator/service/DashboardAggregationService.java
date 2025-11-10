package com.bigbrightpaints.erp.orchestrator.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DashboardAggregationService {

    private final IntegrationCoordinator integrationCoordinator;

    public DashboardAggregationService(IntegrationCoordinator integrationCoordinator) {
        this.integrationCoordinator = integrationCoordinator;
    }

    public Map<String, Object> adminDashboard(String companyId) {
        return integrationCoordinator.fetchAdminDashboard(companyId);
    }

    public Map<String, Object> factoryDashboard(String companyId) {
        return integrationCoordinator.fetchFactoryDashboard(companyId);
    }

    public Map<String, Object> financeDashboard(String companyId) {
        return integrationCoordinator.fetchFinanceDashboard(companyId);
    }
}
