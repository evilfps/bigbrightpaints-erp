package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@ExtendWith(MockitoExtension.class)
class AccountingConfigurationControllerTest {

  @Mock private ConfigurationHealthService configurationHealthService;
  @Mock private CompanyContextService companyContextService;

  @Test
  void health_returnsCompanyScopedHealthReport() {
    Company company = new Company();
    company.setCode("BBP");
    ConfigurationHealthService.ConfigurationHealthReport report =
        new ConfigurationHealthService.ConfigurationHealthReport(true, List.of());
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(configurationHealthService.evaluateCompany(company)).thenReturn(report);
    AccountingConfigurationController controller =
        new AccountingConfigurationController(configurationHealthService, companyContextService);

    ResponseEntity<ApiResponse<ConfigurationHealthService.ConfigurationHealthReport>> response =
        controller.health();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().message()).isEqualTo("Configuration health report");
    assertThat(response.getBody().data()).isSameAs(report);
    verify(companyContextService).requireCurrentCompany();
    verify(configurationHealthService).evaluateCompany(company);
  }
}
