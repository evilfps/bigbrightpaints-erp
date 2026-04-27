package com.bigbrightpaints.erp.modules.accounting.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService.ConfigurationHealthReport;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/accounting/configuration")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class AccountingConfigurationController {

  private final AccountingService accountingService;

  public AccountingConfigurationController(AccountingService accountingService) {
    this.accountingService = accountingService;
  }

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<ConfigurationHealthReport>> health() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Configuration health report", accountingService.getConfigurationHealthReport()));
  }
}
