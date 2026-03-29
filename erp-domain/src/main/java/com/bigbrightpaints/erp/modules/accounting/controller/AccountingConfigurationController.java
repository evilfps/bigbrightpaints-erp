package com.bigbrightpaints.erp.modules.accounting.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/accounting/configuration")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class AccountingConfigurationController {

  private final ConfigurationHealthService configurationHealthService;
  private final CompanyContextService companyContextService;

  public AccountingConfigurationController(
      ConfigurationHealthService configurationHealthService,
      CompanyContextService companyContextService) {
    this.configurationHealthService = configurationHealthService;
    this.companyContextService = companyContextService;
  }

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<ConfigurationHealthService.ConfigurationHealthReport>>
      health() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Configuration health report",
            configurationHealthService.evaluateCompany(
                companyContextService.requireCurrentCompany())));
  }
}
