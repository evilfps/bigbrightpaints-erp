package com.bigbrightpaints.erp.orchestrator.controller;

import com.bigbrightpaints.erp.orchestrator.service.DashboardAggregationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orchestrator/dashboard")
public class DashboardController {

    private final DashboardAggregationService dashboardAggregationService;

    public DashboardController(DashboardAggregationService dashboardAggregationService) {
        this.dashboardAggregationService = dashboardAggregationService;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> adminDashboard(@RequestHeader("X-Company-Id") String companyId) {
        return ResponseEntity.ok(dashboardAggregationService.adminDashboard(companyId));
    }

    @GetMapping("/factory")
    @PreAuthorize("hasAuthority('ROLE_FACTORY')")
    public ResponseEntity<Map<String, Object>> factoryDashboard(@RequestHeader("X-Company-Id") String companyId) {
        return ResponseEntity.ok(dashboardAggregationService.factoryDashboard(companyId));
    }

    @GetMapping("/finance")
    @PreAuthorize("hasAuthority('ROLE_FINANCE')")
    public ResponseEntity<Map<String, Object>> financeDashboard(@RequestHeader("X-Company-Id") String companyId) {
        return ResponseEntity.ok(dashboardAggregationService.financeDashboard(companyId));
    }
}
